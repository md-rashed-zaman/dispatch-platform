package dev.rashed.dispatch.technician;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface TechnicianRepository extends JpaRepository<Technician, UUID> {}
