package com.kira.server.controller;


import com.kira.server.service.IEgineeringParametersService;
import io.swagger.annotations.Api;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 工程参数 前端控制器
 * </p>
 *
 * @author kira
 * @since 2024-11-16
 */
@Api(tags = "工程参数")
@RestController
@RequestMapping("/drilling/egineering")
public class EgineeringParametersController {

    @Autowired
    private IEgineeringParametersService service;





}
