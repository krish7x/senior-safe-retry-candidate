package com.complyance.assignment.retry.application;

import com.complyance.assignment.retry.domain.TaskEntity;
import com.complyance.assignment.retry.domain.TaskNotFoundException;
import com.complyance.assignment.retry.domain.TaskRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskQueryService {

    private final TaskRepository taskRepository;

    TaskQueryService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Transactional(readOnly = true)
    public List<TaskView> list(String tenantId) {
        return taskRepository.findByTenantIdOrderByIdAsc(tenantId).stream()
                .map(TaskView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public TaskView get(String tenantId, String taskId) {
        return taskRepository.findByIdAndTenantId(taskId, tenantId)
                .map(TaskView::from)
                .orElseThrow(TaskNotFoundException::new);
    }

    public record TaskView(String id, String workflowId, String title, String status, long version) {
        static TaskView from(TaskEntity task) {
            return new TaskView(
                    task.getId(),
                    task.getWorkflowId(),
                    task.getTitle(),
                    task.getStatus().name(),
                    task.getVersion());
        }
    }
}
