package com.moveinsync.rostersync.employee;

import com.moveinsync.rostersync.common.error.NotFoundException;
import com.moveinsync.rostersync.domain.TransportFields;
import com.moveinsync.rostersync.outbox.OutboxRepository;
import com.moveinsync.rostersync.security.CurrentUser;
import com.moveinsync.rostersync.security.TenantUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class EmployeeController {

    private final EmployeeRepository employees;
    private final TransportService transport;
    private final OutboxRepository outbox;

    public EmployeeController(EmployeeRepository employees, TransportService transport, OutboxRepository outbox) {
        this.employees = employees;
        this.transport = transport;
        this.outbox = outbox;
    }

    /** Transport admin / operator view. Another tenant's employee is a 404, never a 403. */
    @GetMapping("/employees/{employeeId}")
    public EmployeeView get(@PathVariable String employeeId) {
        return employees.find(CurrentUser.tenantId(), employeeId).map(EmployeeView::of)
                .orElseThrow(() -> new NotFoundException("employee"));
    }

    /** Change events emitted for one employee (handy to see the outbox at work). */
    @GetMapping("/employees/{employeeId}/events")
    public List<Map<String, Object>> events(@PathVariable String employeeId) {
        String tenant = CurrentUser.tenantId();
        employees.find(tenant, employeeId).orElseThrow(() -> new NotFoundException("employee"));
        return outbox.findForEmployee(tenant, employeeId).stream()
                .map(e -> Map.<String, Object>of("eventId", e.eventId(), "type", e.type(), "version", e.hrVersion(),
                        "createdAt", e.createdAt()))
                .toList();
    }

    public record TransportUpdate(@Size(max = 256) String pickupPoint, @Size(max = 256) String specialAssistance) {
    }

    /** Only the transport admin can write these fields; HR events can never touch them. */
    @PutMapping("/employees/{employeeId}/transport")
    public EmployeeView updateTransport(@PathVariable String employeeId, @Valid @RequestBody TransportUpdate body) {
        TenantUser user = CurrentUser.get();
        return EmployeeView.of(transport.update(user.tenantId(), employeeId,
                new TransportFields(blankToNull(body.pickupPoint()), blankToNull(body.specialAssistance())),
                user.getUsername()));
    }

    /** An employee may read only their own record: the id comes from the credential, not the URL. */
    @GetMapping("/me")
    public EmployeeView me() {
        TenantUser user = CurrentUser.get();
        return employees.find(user.tenantId(), user.employeeId()).map(EmployeeView::of)
                .orElseThrow(() -> new NotFoundException("employee"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
