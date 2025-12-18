package com.makiatox.ai.mcp.server.test.service;

import com.makiatox.ai.mcp.server.test.model.XxxRequest01;
import com.makiatox.ai.mcp.server.test.model.XxxRequest02;
import com.makiatox.ai.mcp.server.test.model.XxxResponse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.util.Random;

@Slf4j
@Service
public class TestService {

    @Tool(description = "Retrieve company employee information")
    public XxxResponse getCompanyEmployee(XxxRequest01 xxxRequest01, XxxRequest02 xxxRequest02) {
        log.info("Query wages and working hours by company and employee. {} {}", xxxRequest01.getCompany(), xxxRequest02.getEmployeeName());

        // This section can actually call the interfaces you need,
        // such as calling an HTTP interface to retrieve data or perform certain operations.

        XxxResponse xxxResponse = new XxxResponse();
        xxxResponse.setSalary(new Random().longs(10000).toString());
        xxxResponse.setDayManHour(String.valueOf(new Random().nextInt(24)));

        return xxxResponse;
    }
}

