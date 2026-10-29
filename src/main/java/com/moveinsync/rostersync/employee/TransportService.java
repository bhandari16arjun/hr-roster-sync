package com.moveinsync.rostersync.employee;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.rostersync.common.error.NotFoundException;
import com.moveinsync.rostersync.domain.ChangeEventType;
import com.moveinsync.rostersync.domain.EmployeeState;
import com.moveinsync.rostersync.domain.TransportFields;
import com.moveinsync.rostersync.outbox.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The ONLY writer of transport-owned fields (pickup point, special assistance).
 * Conflict rule: transport fields are last-write-wins among transport admins (a human UI with one
 * owner per office); they have no relationship to the HR version, and HR can never touch them.
 */
@Service
public class TransportService {

    private final EmployeeRepository employees;
    private final OutboxRepository outbox;
    private final ObjectMapper json;
    private final Clock clock;

    public TransportService(EmployeeRepository employees, OutboxRepository outbox, ObjectMapper json, Clock clock) {
        this.employees = employees;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public EmployeeState update(String tenantId, String employeeId, TransportFields fields, String updatedBy) {
        Instant now = clock.instant();
        EmployeeState current = employees.findAllForUpdate(tenantId, List.of(employeeId)).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("employee"));
        employees.updateTransport(tenantId, employeeId, fields, updatedBy, now);
        EmployeeState updated = current.withTransport(fields);

        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId.toString());
        payload.put("type", ChangeEventType.TRANSPORT_FIELDS_UPDATED.name());
        payload.put("tenantId", tenantId);
        payload.put("employeeId", employeeId);
        payload.put("version", current.hrVersion());
        payload.put("changedFields", List.of("pickupPoint", "specialAssistance"));
        payload.put("source", "TRANSPORT_ADMIN");
        outbox.append(List.of(new OutboxRepository.NewEvent(eventId, tenantId, employeeId,
                ChangeEventType.TRANSPORT_FIELDS_UPDATED, current.hrVersion(), toJson(payload))), now);
        return updated;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise event payload", e);
        }
    }
}
