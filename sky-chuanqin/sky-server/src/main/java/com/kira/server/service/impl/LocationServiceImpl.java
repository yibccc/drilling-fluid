package com.kira.server.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kira.server.domain.entity.Location;
import com.kira.server.mapper.LocationMapper;
import com.kira.server.service.ILocationService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * Location service implementation
 * </p>
 *
 * @author kira
 * @since 2025-05-29
 */
@Service
public class LocationServiceImpl extends ServiceImpl<LocationMapper, Location> implements ILocationService {

}
