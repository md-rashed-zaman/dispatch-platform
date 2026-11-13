package dev.rashed.dispatch.workorder;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One thing the technician must do or prove. Part of the {@link WorkOrder} aggregate: only reachable
 * and changeable through its work order, so it has no repository and no public constructor.
 */
@Entity
@Table(name = "checklist_item")
public class ChecklistItem {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private int position;

    private String description;

    protected ChecklistItem() {} // for JPA

    ChecklistItem(int position, String description) {
        if (description == null || description.isBlank() || description.length() > 500) {
            throw new IllegalArgumentException("Checklist item must be 1 to 500 characters");
        }
        this.position = position;
        this.description = description.strip();
    }

    public UUID id() {
        return id;
    }

    public int position() {
        return position;
    }

    public String description() {
        return description;
    }
}
