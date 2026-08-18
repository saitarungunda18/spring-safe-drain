package io.github.springsafedrain.example;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** In-memory generic work API used only to demonstrate protected request draining. */
@RestController
@RequestMapping("/work")
public class WorkController {
  private final ConcurrentMap<UUID, WorkItem> workItems = new ConcurrentHashMap<>();

  /** Creates a simulated work item whose delay keeps the HTTP request in flight. */
  @PostMapping
  public WorkItem create(@Valid @RequestBody CreateWork request) throws InterruptedException {
    UUID id = UUID.randomUUID();
    WorkItem processing = new WorkItem(id, request.name(), "PROCESSING", Instant.now());
    // The map models example business state only; Safe Drain tracks the surrounding HTTP request.
    workItems.put(id, processing);
    // A configurable delay makes the request's in-flight lifetime observable during a drain.
    Thread.sleep(request.processingDelayMs());
    WorkItem completed = new WorkItem(id, processing.name(), "COMPLETED", processing.createdAt());
    workItems.put(id, completed);
    return completed;
  }

  /** Returns one work item from the process-local demonstration store. */
  @GetMapping("/{id}")
  public WorkItem get(@PathVariable UUID id) {
    WorkItem item = workItems.get(id);
    if (item == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return item;
  }

  /** Validated request payload for the generic work endpoint. */
  public record CreateWork(
      @NotBlank @Size(max = 100) String name, @Min(0) @Max(30000) long processingDelayMs) {}

  /** Immutable response model stored by the in-memory example. */
  public record WorkItem(UUID id, String name, String status, Instant createdAt) {}
}
