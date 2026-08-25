package com.complyance.assignment.retry.api;

import com.complyance.assignment.retry.application.TaskQueryService;
import com.complyance.assignment.retry.application.TaskQueryService.TaskView;
import com.complyance.assignment.security.TenantPrincipal;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskQueryService taskQueryService;

    public TaskController(TaskQueryService taskQueryService) {
        this.taskQueryService = taskQueryService;
    }

    @GetMapping
    TaskListResponse list(@AuthenticationPrincipal TenantPrincipal principal) {
        return new TaskListResponse(taskQueryService.list(principal.tenantId()));
    }

    @GetMapping("/{taskId}")
    TaskView get(
            @AuthenticationPrincipal TenantPrincipal principal,
            @PathVariable String taskId) {
        return taskQueryService.get(principal.tenantId(), taskId);
    }

    record TaskListResponse(List<TaskView> tasks) {
    }
}
