package com.nl2sql.core.agent.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowDefinition {
    private String name;
    private String description;
    private List<WorkflowStep> steps;
}
