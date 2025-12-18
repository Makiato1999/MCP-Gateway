package com.makiatox.ai.mcp.server.test.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class XxxRequest02 {

    @JsonProperty(required = true, value = "employeeCount")
    @JsonPropertyDescription("雇员姓名")
    private String employeeName;

}
