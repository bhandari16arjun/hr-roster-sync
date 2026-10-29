package com.moveinsync.rostersync.booking;

import com.moveinsync.rostersync.common.error.ConflictException;
import com.moveinsync.rostersync.common.error.NotFoundException;
import com.moveinsync.rostersync.employee.EmployeeRepository;
import com.moveinsync.rostersync.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Minimal booking API, enough to demonstrate that a deactivation cancels future bookings. */
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingRepository bookings;
    private final EmployeeRepository employees;

    public BookingController(BookingRepository bookings, EmployeeRepository employees) {
        this.bookings = bookings;
        this.employees = employees;
    }

    public record NewBooking(@NotBlank String employeeId, @NotNull LocalDate tripDate, @Size(max = 8) String seatNo) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingRepository.Booking create(@Valid @RequestBody NewBooking body) {
        String tenant = CurrentUser.tenantId();
        var employee = employees.find(tenant, body.employeeId()).orElseThrow(() -> new NotFoundException("employee"));
        if (!employee.isActive()) {
            throw new ConflictException("EMPLOYEE_INACTIVE", "cannot book a cab for an inactive employee");
        }
        long id = bookings.create(tenant, body.employeeId(), body.tripDate(), body.seatNo());
        return bookings.findForEmployee(tenant, body.employeeId()).stream().filter(b -> b.id() == id)
                .findFirst().orElseThrow();
    }

    @GetMapping
    public List<BookingRepository.Booking> forEmployee(@RequestParam String employeeId) {
        return bookings.findForEmployee(CurrentUser.tenantId(), employeeId);
    }
}
