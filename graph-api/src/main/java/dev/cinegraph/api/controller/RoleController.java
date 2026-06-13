package dev.cinegraph.api.controller;

import dev.cinegraph.api.dto.RoleWeight;
import dev.cinegraph.api.repo.RoleQueryRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final RoleQueryRepository roleRepo;

    public RoleController(RoleQueryRepository roleRepo) {
        this.roleRepo = roleRepo;
    }

    @GetMapping
    public List<RoleWeight> roles() {
        return roleRepo.findAll();
    }
}
