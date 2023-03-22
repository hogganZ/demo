package com.hg.demo;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import com.google.gson.Gson;
import com.lark.oapi.service.approval.v4.enums.*;
import com.lark.oapi.service.approval.v4.model.*;
import com.zonglang.common.core.constant.SecurityConstants;
import com.zonglang.common.core.util.R;
import com.zonglang.erp.api.dto.approve.ApproveInstanceDTO;
import com.zonglang.erp.api.dto.approve.InstanceFormDTO;
import com.zonglang.erp.api.dto.approve.InstanceLinkDTO;
import com.zonglang.erp.api.dto.feishu.FeishuDepartmentDTO;
import com.zonglang.erp.api.dto.feishu.FeishuUserDTO;
import com.zonglang.erp.api.entity.approve.ApproveInstance;
import com.zonglang.erp.service.ApproveInstanceService;
import com.zonglang.erp.service.approve.ExternalApproveInstanceService;
import com.zonglang.erp.service.feishu.FeishuApprovalDefineService;
import com.zonglang.erp.service.feishu.FeishuUserInfoService;
import com.zonglang.erp.util.LocalRedisUtil;
import com.zonglang.erp.util.TimeUtil;
import com.zonglang.feishu.open.service.FsOpenService;
import com.zonglang.flow.api.constant.job.JobResultEnum;
import com.zonglang.flow.api.dto.job.FlowJobDTO;
import com.zonglang.flow.api.feign.RemoteFlowJobService;
import com.zonglang.spring.starter.feishu.open.config.openconfig.FsOpenConfig;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author sanmu
 * @version 1.0
 * @className ApproveInstanceServiceImpl
 * @description
 * @date 2023/3/15 11:13
 **/
@Service
@Slf4j
@AllArgsConstructor
public class ExternalApproveInstanceServiceImpl implements ExternalApproveInstanceService {
    private final FsOpenService fsOpenService;
    private final FeishuApprovalDefineService feishuApprovalDefineService;
    private final FeishuUserInfoService feishuUserInfoService;
    private final RemoteFlowJobService remoteFlowJobService;
    private final LocalRedisUtil localRedisUtil;
    private final ApproveInstanceService approveInstanceService;
    @Override
    public Boolean create(ApproveInstanceDTO approveInstanceDTO) {
        return syncInstance(approveInstanceDTO, ExternalInstanceStatusEnum.PENDING);
    }

    @Override
    public Boolean update(ApproveInstanceDTO approveInstanceDTO) {
        return create(approveInstanceDTO);
    }


    @Override
    public Boolean approve(ApproveInstanceDTO approveInstanceDTO) {
        return syncInstance(approveInstanceDTO, ExternalInstanceStatusEnum.APPROVED);
    }

    @Override
    public Boolean reject(ApproveInstanceDTO approveInstanceDTO) {
        return syncInstance(approveInstanceDTO, ExternalInstanceStatusEnum.REJECTED);
    }

    @Override
    public Boolean delete(ApproveInstanceDTO approveInstanceDTO) {
        return syncInstance(approveInstanceDTO, ExternalInstanceStatusEnum.DELETED);
    }

    /**
     * 审批信息转换为飞书创建审批实例的参数
     *  @param approveInstanceDTO
     * @param existInstance
     * @param status
     */
    private ExternalInstance convertapproveInstanceDTO2ExternalInstance(ApproveInstanceDTO approveInstanceDTO, ApproveInstance existInstance, ExternalInstanceStatusEnum status) {

        String approveCode = feishuApprovalDefineService.getApproveCode(approveInstanceDTO.getSubBusinessType());
        InstanceLinkDTO linkDTO = InstanceLinkDTO.builder().app("erp")
                .instanceId(approveInstanceDTO.getInstanceId())
                .approvalName(approveInstanceDTO.getTitle())
                .businessId(approveInstanceDTO.getBusinessId())
                .businessType(approveInstanceDTO.getBusinessType())
                .subBusinessType(approveInstanceDTO.getSubBusinessType())
                .isElectronic(approveInstanceDTO.getIsElectronic())
                .build();
        ExternalInstance externalInstance = ExternalInstance.newBuilder()
                .approvalCode(approveCode)//根据审批定义
                .instanceId(approveInstanceDTO.getInstanceId().toString())
                .title(approveInstanceDTO.getTitle())
                .status(status)
                .displayMethod(ExternalInstanceDisplayMethodEnum.BROWSER)
                .updateMode(ExternalInstanceUpdateModeEnum.REPLACE)
                .build();
        //发起人信息
        setApplyInfo(externalInstance, approveInstanceDTO);
        //设置跳转链接
        setLinks(externalInstance,linkDTO);
        //设置审批实例时间
        setInstanceTime(externalInstance,existInstance, status);
        //设置节点信息
        setNodeList(externalInstance, approveInstanceDTO.getTitle(), linkDTO);
        //设置国际化文案
        setI18n(externalInstance);
        //设置表单信息
        setForm(externalInstance, approveInstanceDTO.getInstanceFormDTOs());
        return externalInstance;
    }

    private void setLinks(ExternalInstance externalInstance, InstanceLinkDTO linkDTO) {
        externalInstance.setLinks(getInstanceLink(linkDTO));
    }

    /**
     * 设置审批表单信息
     *
     * @param externalInstance
     * @param instanceFormDTO
     */
    private void setForm(ExternalInstance externalInstance, List<InstanceFormDTO> instanceFormDTO) {
        externalInstance.setForm(convert2InstanceForm(externalInstance, instanceFormDTO));
    }

    /**
     * 转换表单信息
     * @param externalInstance
     * @param instanceFormDTOs
     * @return
     */
    private ExternalInstanceForm[] convert2InstanceForm(ExternalInstance externalInstance, List<InstanceFormDTO> instanceFormDTOs) {
        I18nResource[] i18nResources = externalInstance.getI18nResources();
        if (i18nResources == null) {
            i18nResources = new I18nResource[1];
        }
        if (i18nResources[0] == null) {
            i18nResources[0] = I18nResource.newBuilder()
                    .locale(I18nResourceLocaleEnum.ZHCN)
                    .isDefault(Boolean.TRUE)
                    .texts(null)
                    .build();
        }
        ExternalInstanceForm[] externalInstanceForm = new ExternalInstanceForm[instanceFormDTOs.size()];
        I18nResourceText[] i18nResourceTexts = new I18nResourceText[instanceFormDTOs.size() * 2];
        for (int i = 0; i < instanceFormDTOs.size(); i++) {
            InstanceFormDTO instanceFormDTO = instanceFormDTOs.get(i);
            I18nResourceText name = getI18nResourceText("formName" + i, instanceFormDTO.getName());
            I18nResourceText value = getI18nResourceText("formValue"+i, instanceFormDTO.getValue());
            i18nResourceTexts[i * 2] = name;
            i18nResourceTexts[i * 2 + 1] = value;
            ExternalInstanceForm form = ExternalInstanceForm.newBuilder().name(name.getKey()).value(value.getKey()).build();
            externalInstanceForm[i] = form;
        }
        if (i18nResources[0].getTexts() == null) {
            i18nResources[0].setTexts(i18nResourceTexts);
        }else{
            i18nResources[0].setTexts(ArrayUtil.addAll(i18nResources[0].getTexts(), i18nResourceTexts));
        }
        externalInstance.setI18nResources(i18nResources);
        return externalInstanceForm;
    }

    /**
     * 生成国际化文案对象
     * @param key
     * @param name
     * @return
     */
    private I18nResourceText getI18nResourceText(String key, String name) {
        return I18nResourceText.newBuilder().key("@i18n@"+key).value(name).build();
    }

    /**
     * 设置国际化文案
     *
     * @param externalInstance
     */
    private void setI18n(ExternalInstance externalInstance) {
        externalInstance.setI18nResources(null);
    }


    /**
     * 设置节点信息
     *
     * @param externalInstance
     * @param linkDTO
     */
    private void setNodeList(ExternalInstance externalInstance, String title, InstanceLinkDTO linkDTO) {
        R<List<FlowJobDTO>> result = remoteFlowJobService.getJobsByInstanceId(Long.valueOf(externalInstance.getInstanceId()), SecurityConstants.FROM_IN);
        List<FlowJobDTO> flowJobDTOS = null;
        if (result != null && result.getCode() == 0) {
            flowJobDTOS = result.getData();
        }
        Assert.notEmpty(flowJobDTOS, "获取审批节点失败");
        //遍历审批节点 生成节点信息
        List<ExternalInstanceTaskNode> taskNodes = new ArrayList<>();
        List<CcNode> ccNodes = new ArrayList<>();
        for (FlowJobDTO flowJobDTO : flowJobDTOS) {
            if (JobResultEnum.COPYED.getValue().equals(flowJobDTO.getResult())) {
                ccNodes.add(convertFlowJobDTO2CcNode(flowJobDTO, title, linkDTO));
            } else {
                taskNodes.add(convertFlowJobDTO2TaskNode(flowJobDTO, title, linkDTO));
            }
        }

        externalInstance.setTaskList(taskNodes.toArray(new ExternalInstanceTaskNode[0]));
        externalInstance.setCcList(ccNodes.toArray(new CcNode[0]));
    }

    /**
     * ERP审批任务转抄送节点
     *
     * @param flowJobDTO
     * @param title
     * @param linkDTO
     * @return
     */
    private CcNode convertFlowJobDTO2CcNode(FlowJobDTO flowJobDTO, String title, InstanceLinkDTO linkDTO) {
        FeishuUserDTO feishuUserInfo = feishuUserInfoService.getFeishuUserInfo(flowJobDTO.getUserId());
        if (feishuUserInfo == null) {
            return null;
        }
        return CcNode.newBuilder()
                .createTime(TimeUtil.localDateTimeToTimestampStr(flowJobDTO.getStartTime()))
                .title(title)
                .ccId(String.valueOf(flowJobDTO.getId()))
                .links(getInstanceLink(linkDTO))
                .openId(feishuUserInfo.getOpenId())
                .readStatus(CcNodeReadStatusEnum.READ)
                .updateTime(TimeUtil.localDateTimeToTimestampStr(flowJobDTO.getUpdateTime()))
                .build();
    }
    /**
     * ERP审批任务转审批节点
     *
     * @param flowJobDTO
     * @param title
     * @param linkDTO
     * @return
     */
    private ExternalInstanceTaskNode convertFlowJobDTO2TaskNode(FlowJobDTO flowJobDTO, String title, InstanceLinkDTO linkDTO) {
        FeishuUserDTO feishuUserInfo = feishuUserInfoService.getFeishuUserInfo(flowJobDTO.getUserId());
        if (feishuUserInfo == null) {
            return null;
        }
        return ExternalInstanceTaskNode.newBuilder()
                .createTime(TimeUtil.localDateTimeToTimestampStr(flowJobDTO.getStartTime()))
                .endTime(TimeUtil.localDateTimeToTimestampStr(flowJobDTO.getEndTime()))
                .updateTime(TimeUtil.localDateTimeToTimestampStr(flowJobDTO.getUpdateTime()))
                .links(getInstanceLink(linkDTO))
                .openId(feishuUserInfo.getOpenId())
                .status(convertFlowJobResult2TaskStatus(flowJobDTO.getResult()))
                .taskId(String.valueOf(flowJobDTO.getId()))
                .title(title)
                .actionContext("")
                .actionConfigs(null)
                .build();
    }

    /**
     * 审批跳转链接
     * @param instanceLinkDTO
     * @return
     */
    public ExternalInstanceLink getInstanceLink(InstanceLinkDTO instanceLinkDTO) {
        StringBuffer stringBuffer = new StringBuffer();
        String params = stringBuffer
                .append("&").append("app=").append(instanceLinkDTO.getApp())
                .append("&").append("businessType=").append(instanceLinkDTO.getBusinessType())
                .append("&").append("subBusinessType=").append(instanceLinkDTO.getSubBusinessType())
                .append("&").append("businessId=").append(instanceLinkDTO.getBusinessId())
                .append("&").append("instanceId=").append(instanceLinkDTO.getInstanceId())
                .append("&").append("approvalName=").append(StrUtil.isNotEmpty(instanceLinkDTO.getApprovalName()) ? instanceLinkDTO.getApprovalName() : "审批中心")
                .append("&").append("isElectronic=").append(instanceLinkDTO.getIsElectronic())
                .toString();
        return ExternalInstanceLink.newBuilder().pcLink(getPcLink(params)).mobileLink(getMobileLink(params)).build();
    }

    /**
     * 移动端跳转链接
     *
     * @param params
     * @return
     */
    private String getMobileLink(String params) {
        FsOpenConfig fsOpenConfig = localRedisUtil.getFsOpenConfig();
        return fsOpenConfig.getApprove().getDefaultUrl() + URLUtil.encode(params);
    }

    /**
     * PC端跳转链接
     *
     * @param params
     * @return
     */
    private String getPcLink(String params) {
        FsOpenConfig fsOpenConfig = localRedisUtil.getFsOpenConfig();
        return fsOpenConfig.getApprove().getDefaultUrl() + URLUtil.encode(params);
    }

    /**
     * 审批任务状态转审批节点状态
     * @param result
     * @return
     */
    private ExternalInstanceTaskNodeStatusEnum convertFlowJobResult2TaskStatus(Integer result) {
        if (JobResultEnum.WAIT_HANDLE.getValue().equals(result)) {
            return ExternalInstanceTaskNodeStatusEnum.PENDING;
        } else if (JobResultEnum.AGREE.getValue().equals(result)) {
            return ExternalInstanceTaskNodeStatusEnum.APPROVED;
        } else  {
            return ExternalInstanceTaskNodeStatusEnum.REJECT;
        }
    }


    /**
     * 审批实例时间
     *  @param externalInstance
     * @param existInstance
     * @param status
     */
    private void setInstanceTime(ExternalInstance externalInstance, ApproveInstance existInstance, ExternalInstanceStatusEnum status) {
        if (ExternalInstanceStatusEnum.PENDING.equals(status)) {
            externalInstance.setStartTime(TimeUtil.localDateTimeToTimestampStr(LocalDateTime.now()));
            externalInstance.setUpdateTime(TimeUtil.localDateTimeToTimestampStr(LocalDateTime.now()));
            externalInstance.setEndTime("0");
        } else {
            if (existInstance != null) {
                externalInstance.setStartTime(existInstance.getStartTime().toString());
            }
            externalInstance.setEndTime(TimeUtil.localDateTimeToTimestampStr(LocalDateTime.now()));
            externalInstance.setUpdateTime(TimeUtil.localDateTimeToTimestampStr(LocalDateTime.now()));
        }
    }

    /**
     * 设置审批发起人信息
     *
     * @param externalInstance
     * @param approveInstanceDTO
     */
    private void setApplyInfo(ExternalInstance externalInstance, ApproveInstanceDTO approveInstanceDTO) {
        if (approveInstanceDTO.getUserId() != null) {
            //员工发起
            FeishuUserDTO feishuUserInfo = feishuUserInfoService.getFeishuUserInfo(approveInstanceDTO.getUserId());
            Assert.isTrue(feishuUserInfo != null, "审批发起人未绑定飞书,");
            externalInstance.setOpenId(feishuUserInfo.getOpenId());

            if (CollectionUtils.isEmpty(feishuUserInfo.getDepartments())) {
                //未关联部门
                externalInstance.setDepartmentName("无");
            } else {
                //取第一个部门
                FeishuDepartmentDTO feishuDepartmentDTO = feishuUserInfo.getDepartments().get(0);
                Assert.notNull(feishuDepartmentDTO, "获取审批发起人部门失败");
                externalInstance.setDepartmentId(feishuDepartmentDTO.getDepartmentId());
            }

        } else {
            //非员工发起人
            externalInstance.setUserName(approveInstanceDTO.getUserName());
            externalInstance.setDepartmentName(approveInstanceDTO.getDepartmentName());
        }

    }

    /**
     * 同步审批实例到飞书
     *
     * @param approveInstanceDTO
     * @return
     */
    public Boolean syncInstance(ApproveInstanceDTO approveInstanceDTO, ExternalInstanceStatusEnum status) {
        //设置实例固有属性
        ApproveInstance existInstance = approveInstanceService.getByInstanceId(approveInstanceDTO.getInstanceId());

        ExternalInstance externalInstance = convertapproveInstanceDTO2ExternalInstance(approveInstanceDTO,existInstance, status);

        syncApproveInstance(externalInstance, existInstance);

        CreateExternalInstanceReq createExternalInstanceReq = CreateExternalInstanceReq.newBuilder()
                .externalInstance(externalInstance)
                .build();

        try {
            log.info("创建审批实例 {}", new Gson().toJson(externalInstance));
            CreateExternalInstanceResp createExternalInstanceResp = fsOpenService.getClient().approval().externalInstance().create(createExternalInstanceReq);
            if (createExternalInstanceResp.success()) {
                log.info("审批实例 {} 创建完成",externalInstance.getTitle());
            } else {
                log.info("审批实例 {} 创建失败 {}",externalInstance.getTitle(),createExternalInstanceResp.getMsg());
            }
            return createExternalInstanceResp.success();
        } catch (Exception e) {
            log.error("创建飞书审批实例异常 {}", e.getMessage());
        }
        return Boolean.FALSE;
    }

    /**
     * 同步本地审批信息
     * @param externalInstance
     * @param existInstance
     */
    private void syncApproveInstance(ExternalInstance externalInstance, ApproveInstance existInstance) {
        if (existInstance == null) {
            existInstance = BeanUtil.toBean(externalInstance, ApproveInstance.class);
        }else{
            BeanUtil.copyProperties(externalInstance,existInstance);
        }
        approveInstanceService.saveOrUpdate(existInstance);
    }
}
