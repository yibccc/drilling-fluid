package com.kira.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kira.server.domain.dto.EmployeeDTO;
import com.kira.server.domain.dto.EmployeeLoginDTO;
import com.kira.server.domain.dto.EmployeePageQueryDTO;
import com.kira.server.domain.entity.Employee;
import com.kira.common.result.PageResult;

public interface EmployeeService extends IService<Employee> {

    /**
     * 员工登录
     * @param employeeLoginDTO
     * @return
     */
    Employee login(EmployeeLoginDTO employeeLoginDTO);

    /**
     * 新增员工
     * @param employeeDTO
     */
    void save(EmployeeDTO employeeDTO);

    /**
     * 分页查询
     * @param employeePageQueryDTO
     * @return
     */
    PageResult pageQuery(EmployeePageQueryDTO employeePageQueryDTO);

    /**
     * 启用禁用员工账号
     * @param status
     * @param id
     */
    void startOrStop(Integer status, Long id);

    /**
     * 根据id查询员工
     * @param id
     * @return
     */
    Employee getById(Long id);

    /**
     * 编辑员工信息
     * @param employeeDTO
     */
    void update(EmployeeDTO employeeDTO);

    /**
     * 根据用户名查询员工
     * @param username
     * @return
     */
    Employee getByUsername(String username);
}
