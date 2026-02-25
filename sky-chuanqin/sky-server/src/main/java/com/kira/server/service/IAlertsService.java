package com.kira.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kira.server.domain.vo.AlertQueryVO;
import com.kira.server.domain.dto.ExpertDTO;
import com.kira.server.domain.dto.ExpertMDTO;
import com.kira.server.domain.entity.Alerts;
import com.kira.server.domain.query.PageDTO;
import com.kira.server.domain.query.AlertQuery;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author kira
 * @since 2024-10-06
 */
public interface IAlertsService extends IService<Alerts> {

    void expertChange(ExpertDTO dto);

    PageDTO<AlertQueryVO> queryByDTO(AlertQuery query);

    void expertMessage(ExpertMDTO dto);

    String queryVOById(Long id);
}
