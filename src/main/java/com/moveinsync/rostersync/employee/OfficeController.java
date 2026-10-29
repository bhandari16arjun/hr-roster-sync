package com.moveinsync.rostersync.employee;

import com.moveinsync.rostersync.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/offices")
public class OfficeController {

    private final OfficeService offices;

    public OfficeController(OfficeService offices) {
        this.offices = offices;
    }

    @GetMapping
    public List<OfficeService.Office> list() {
        return offices.list(CurrentUser.tenantId());
    }

    public record NewOffice(@NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{2,32}$") String code,
                            @NotBlank @Size(max = 128) String name) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OfficeService.Office create(@Valid @RequestBody NewOffice body) {
        return offices.create(CurrentUser.tenantId(), body.code(), body.name());
    }
}
