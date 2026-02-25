package com.kira.server.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kira.server.domain.entity.EgineeringParameters;
import com.kira.server.mapper.EgineeringParametersMapper;
import com.kira.server.service.IEgineeringParametersService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 工程参数 服务实现类
 * </p>
 *
 * @author kira
 * @since 2024-11-16
 */
@Service
public class EgineeringParametersServiceImpl extends ServiceImpl<EgineeringParametersMapper, EgineeringParameters> implements IEgineeringParametersService {

    @Autowired
    private EgineeringParametersMapper mapper;

}
