package com.iskeru.computeadmin.machine.model;

import com.iskeru.computeadmin.auth.model.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * A machine label, unique <strong>per owner</strong>: two users may both have a
 * {@code prod} tag, but one user cannot have it twice. Get-or-created by
 * {@code MachineService} when tagging. Not Envers-audited — labels only.
 *
 * <p>spec-003.
 */
@Entity
@Table(name = "tag", uniqueConstraints = {
        @UniqueConstraint(name = "uq_tag_owner_name", columnNames = {"owner_id", "name"})
})
@Getter
@Setter
@NoArgsConstructor
public class Tag {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_tag_owner"))
    private AppUser owner;

    @Column(nullable = false, length = 255)
    private String name;
}
