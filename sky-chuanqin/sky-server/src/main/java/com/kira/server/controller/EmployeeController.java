package com.kira.server.controller;

import com.kira.common.result.PageResult;
import com.kira.common.result.Result;
import com.kira.server.domain.dto.EmployeeDTO;
import com.kira.server.domain.dto.EmployeeLoginDTO;
import com.kira.server.domain.dto.EmployeePageQueryDTO;
import com.kira.server.domain.dto.ResetPasswdDTO;
import com.kira.server.domain.entity.Employee;
import com.kira.server.domain.vo.EmployeeLoginVO;
import com.kira.server.service.EmployeeService;
import com.kira.server.utils.JwtTokenUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

/**
 * 员工管理
 */
@RestController
@RequestMapping("/admin/employee")
@Slf4j
@Api(tags = "员工相关接口")
public class EmployeeController {

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private JwtTokenUtil jwtTokenUtil;
    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * 登录
     *
     * @param employeeLoginDTO
     * @return
     */
    @PostMapping("/login")
    @ApiOperation(value = "员工登录")
    public Result<EmployeeLoginVO> login(@RequestBody EmployeeLoginDTO employeeLoginDTO) {
        log.info("员工登录：{}", employeeLoginDTO);

        Authentication authenticate = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(
                    employeeLoginDTO.getUsername(),
                    employeeLoginDTO.getPassword()
            )
        );

        Employee employee = employeeService.getByUsername(employeeLoginDTO.getUsername());

        // 生成JWT token
        String token = jwtTokenUtil.createToken(employee.getUsername(), employee.getRole());

        // 构建响应数据
        EmployeeLoginVO employeeLoginVO = EmployeeLoginVO.builder()
                .id(employee.getId())
                .userName(employee.getUsername())
                .name(employee.getName())
                .token(token)
                .role(employee.getRole())
                .build();

        return Result.success(employeeLoginVO);
    }

    /**
     * 退出
     *
     * @return
     */
    @PostMapping("/logout")
    @ApiOperation("员工退出")
    public Result<String> logout() {
        return Result.success();
    }

    /**
     * 新增员工
     * @param employeeDTO
     * @return
     */
    @PostMapping
    @ApiOperation("新增员工")
    @PreAuthorize("hasRole('ADMIN')")
    public Result save(@RequestBody EmployeeDTO employeeDTO){
        log.info("新增员工：{}",employeeDTO);
        employeeService.save(employeeDTO);
        return Result.success();
    }

    /**
     * 员工分页查询
     * @param employeePageQueryDTO
     * @return
     */
    @GetMapping("/page")
    @ApiOperation("员工分页查询")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PageResult> page(EmployeePageQueryDTO employeePageQueryDTO){
        log.info("员工分页查询，参数为：{}", employeePageQueryDTO);
        PageResult pageResult = employeeService.pageQuery(employeePageQueryDTO);
        return Result.success(pageResult);
    }

    /**
     * 启用禁用员工账号
     * @param status
     * @param id
     * @return
     */
    @PostMapping("/status/{status}")
    @ApiOperation("启用禁用员工账号")
    @PreAuthorize("hasRole('ADMIN')")
    public Result startOrStop(@PathVariable Integer status,Long id){
        log.info("启用禁用员工账号：{},{}",status,id);
        employeeService.startOrStop(status,id);
        return Result.success();
    }

    /**
     * 根据id查询员工信息
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    @ApiOperation("根据id查询员工信息")
    public Result<Employee> getById(@PathVariable Long id){
        Employee employee = employeeService.getById(id);
        return Result.success(employee);
    }

    @PostMapping("/pwd")
    @ApiOperation("修改用户密码")
    public Result updatePwd(@RequestBody ResetPasswdDTO passwordDto){
        String encode = passwordEncoder.encode(passwordDto.getPassword());
        Employee employee = new Employee();
        employee.setId(passwordDto.getId());
        employee.setPassword(encode);
        employeeService.updateById(employee);
        return Result.success();
    }

    /**
     * 编辑员工信息
     * @param employeeDTO
     * @return
     */
    @PutMapping
    @ApiOperation("编辑员工信息")
    @PreAuthorize("hasRole('ADMIN')")
    public Result update(@RequestBody EmployeeDTO employeeDTO){
        log.info("编辑员工信息：{}", employeeDTO);
        employeeService.update(employeeDTO);
        return Result.success();
    }
}
