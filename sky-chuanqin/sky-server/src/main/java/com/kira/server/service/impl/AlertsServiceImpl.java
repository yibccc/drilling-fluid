package com.kira.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kira.server.domain.vo.AlertQueryVO;
import com.kira.common.context.BaseContext;
import com.kira.server.domain.dto.ExpertDTO;
import com.kira.server.domain.dto.ExpertMDTO;
import com.kira.server.domain.entity.Alerts;
import com.kira.server.mapper.AlertsMapper;
import com.kira.server.domain.query.PageDTO;
import com.kira.server.service.IAlertsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.kira.server.domain.query.AlertQuery;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author kira
 * @since 2024-10-06
 */
@Service
public class AlertsServiceImpl extends ServiceImpl<AlertsMapper, Alerts> implements IAlertsService {

    @Autowired
    private AlertsMapper alertsMapper;


    /**
     * 专家介入
     * @param dto
     */
    public void expertChange(ExpertDTO dto) {
        Alerts alerts = new Alerts();
        alerts.setId(dto.getId());
        if(dto.getIsInstrument())
            alerts.setIsInstrument(true);
        if (dto.getIsData())
            alerts.setIsData(true);
        alerts.setExpertContext(dto.getExpertContext());
        alerts.setUpdateTime(LocalDateTime.now());
        alerts.setUpdateUser(BaseContext.getCurrentId());
        alerts.setIsPth(false);
        alertsMapper.updateById(alerts);
    }

    /**
     * 分页查询
     * @param query
     * @return
     */
    public PageDTO<AlertQueryVO> queryByDTO(AlertQuery query) {
        // 1.构建条件
        // 1.1.分页条件
        Page<Alerts> page = Page.of(query.getPageNo(), query.getPageSize());
        // 1.2.排序条件
        if (query.getSortBy() != null) {
            page.addOrder(new OrderItem(query.getSortBy(), query.getIsAsc()));
        }else{
            // 默认按照更新时间排序
            page.addOrder(new OrderItem("update_time", false));
        }
        // 1.3. 构建查询条件，匹配 wellId
//        QueryWrapper<DrillingData> queryWrapper = new QueryWrapper<>();
//        if (query.getWellId() != null) {
//            queryWrapper.eq("well_id", query.getWellId());
//            queryWrapper.eq("")
//        }
        LambdaQueryWrapper<Alerts> queryWrapper = new LambdaQueryWrapper<Alerts>()
                .eq(Alerts::getWellId, query.getWellId())
                .ge(Alerts::getCreateTime, query.getStartTime())
                .le(Alerts::getAlertTime, query.getEndTime())
                .ge(Alerts::getValue,0.5)
                .eq(Alerts::getIsPth, true);
        if (query.getAlertTitle() != null && !query.getAlertTitle().isEmpty()) {
            queryWrapper.like(Alerts::getMessage, query.getAlertTitle());
        }

        queryWrapper.orderByDesc(Alerts::getValue);

        // 2. 查询
        page(page, queryWrapper);

        // 3.数据非空校验
        List<Alerts> records = page.getRecords();
        if (records == null || records.size() <= 0) {
            // 无数据，返回空结果
            return new PageDTO<>(page.getTotal(), page.getPages(), Collections.emptyList());
        }
        // 4.有数据，转换
        List<AlertQueryVO> list = BeanUtil.copyToList(records, AlertQueryVO.class);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getUpdateUser()==null){
                list.get(i).setUpdateUser(alertsMapper.findUserName(records.get(i).getCreateUser()));
            }
            list.get(i).setUpdateUser(alertsMapper.findUserName(records.get(i).getUpdateUser()));
            LocalDateTime dateTime1 = list.get(i).getCreateTime();
            LocalDateTime dateTime2 = list.get(i).getAlertTime();
            long secondsBetween = ChronoUnit.SECONDS.between(dateTime1, dateTime2);
            int differenceInSeconds = (int) secondsBetween;
            list.get(i).setTime(differenceInSeconds);
        }
        // 5.封装返回
        return new PageDTO<AlertQueryVO>(page.getTotal(), page.getPages(), list);
    }

    /**
     * 专家完善信息
     * @param dto
     */
    public void expertMessage(ExpertMDTO dto) {

        Alerts alerts = new Alerts();
        alerts.setId(dto.getId());
        alerts.setExpertContext(dto.getExpertContext());
        alerts.setUpdateTime(LocalDateTime.now());
        alerts.setUpdateUser(BaseContext.getCurrentId());
        if (alerts.getUpdateUser() == null)
            alerts.setUpdateUser(1L);
        alerts.setMessage(dto.getMessage());
        if (dto.getTips()!=null && !dto.getTips().isEmpty()) {
            alerts.setTips(dto.getTips());
        }
        alertsMapper.updateById(alerts);
    }

    /**
     * 根据id查询
     * @param id
     * @return
     */
    public String queryVOById(Long id) {
        Alerts alerts = alertsMapper.selectById(id);
        return alerts.getExpertContext();
    }

    /**
     * 根据id查询详细信息
     * @param id
     * @return
     */
    public String queryDetailById(Long id) {
        Alerts alerts = alertsMapper.selectById(id);
        return alerts.getExpertContext();
    }
}
