package org.cce.admin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * gateway-service's {@code api_permissions} table, in the gateway's own shape (section 6, DEV-1).
 * The {@code TIMESTAMP} columns are written only by the database defaults and trigger.
 */
@Entity
@Table(name = "api_permissions")
public class ApiPermissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "permission_name", nullable = false, length = 100)
    private String permissionName;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod;

    @Column(name = "uri_pattern", nullable = false, length = 255)
    private String uriPattern;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "resource_category", length = 50)
    private String resourceCategory;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPermissionName() {
        return permissionName;
    }

    public void setPermissionName(String permissionName) {
        this.permissionName = permissionName;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getUriPattern() {
        return uriPattern;
    }

    public void setUriPattern(String uriPattern) {
        this.uriPattern = uriPattern;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getResourceCategory() {
        return resourceCategory;
    }

    public void setResourceCategory(String resourceCategory) {
        this.resourceCategory = resourceCategory;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
