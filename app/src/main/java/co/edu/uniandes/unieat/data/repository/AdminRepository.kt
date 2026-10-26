package co.edu.uniandes.unieat.data.repository

import co.edu.uniandes.unieat.core.model.AdminRoleBody
import co.edu.uniandes.unieat.core.model.AdminRoleResponse
import co.edu.uniandes.unieat.core.model.AdminUser
import co.edu.uniandes.unieat.core.model.AdminUsersResponse
import co.edu.uniandes.unieat.core.model.ApprovalBody
import co.edu.uniandes.unieat.core.model.PendingMembership
import co.edu.uniandes.unieat.core.model.PendingMembershipsResponse
import co.edu.uniandes.unieat.data.remote.ApiClient
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonObject

/** Admin-only calls. The backend checks the admin role again on every one. */
interface AdminRepository {
    suspend fun pendingMemberships(): List<PendingMembership>

    /** Approving makes that user a restaurant for that establishment. */
    suspend fun approve(membership: PendingMembership)

    suspend fun users(): List<AdminUser>

    /** [admin] true grants the admin role, false takes it back (the user returns to student). */
    suspend fun setAdmin(userId: String, admin: Boolean): AdminUser
}

class RemoteAdminRepository(private val api: ApiClient) : AdminRepository {
    override suspend fun pendingMemberships(): List<PendingMembership> =
        api.get<PendingMembershipsResponse>("admin/memberships", mapOf("status" to "pending")).memberships

    override suspend fun approve(membership: PendingMembership) {
        api.send<JsonObject, ApprovalBody>(
            HttpMethod.Post,
            "admin/memberships/approve",
            ApprovalBody(membership.establishmentId, membership.userId),
        )
    }

    override suspend fun users(): List<AdminUser> = api.get<AdminUsersResponse>("admin/users").users

    override suspend fun setAdmin(userId: String, admin: Boolean): AdminUser {
        val response = api.send<AdminRoleResponse, AdminRoleBody>(
            HttpMethod.Patch,
            "admin/users/$userId/role",
            AdminRoleBody(if (admin) "admin" else "student"),
        )
        return AdminUser(response.id, "", response.role)
    }
}

/** Demo mode has no other users and no requests. */
class FakeAdminRepository : AdminRepository {
    override suspend fun pendingMemberships(): List<PendingMembership> = emptyList()

    override suspend fun approve(membership: PendingMembership) = Unit

    override suspend fun users(): List<AdminUser> = emptyList()

    override suspend fun setAdmin(userId: String, admin: Boolean): AdminUser = AdminUser(userId, "", if (admin) "admin" else "student")
}
