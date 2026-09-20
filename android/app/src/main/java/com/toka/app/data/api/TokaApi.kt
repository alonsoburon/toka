package com.toka.app.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface TokaApi {

    /** Sonda de disponibilidad, sin auth. Se usa para validar una URL de servidor. */
    @GET("healthz")
    suspend fun health(): HealthResponse

    // ── Sincronización offline ────────────────────────────────────────────────

    /** Todo lo que cambió en el household después del cursor. */
    @GET("sync")
    suspend fun pull(
        @Query("since") since: Long,
        @Header("Authorization") token: String
    ): SyncPullResponse

    /** Sube la cola de escrituras. Reenviar es seguro: el servidor deduplica. */
    @POST("sync/mutations")
    suspend fun push(
        @Body request: PushRequest,
        @Header("Authorization") token: String
    ): PushResponse


    @POST("households")
    suspend fun createHousehold(
        @Body request: CreateHouseholdRequest
    ): CreateHouseholdResponse

    @POST("households/join")
    suspend fun joinHousehold(
        @Body request: JoinHouseholdRequest
    ): JoinHouseholdResponse

    @POST("households/{hid}/leave")
    suspend fun leaveHousehold(
        @Path("hid") householdId: Long,
        @Header("Authorization") token: String
    ): Response<Unit>

    @GET("households/{hid}/people")
    suspend fun getPeople(
        @Path("hid") householdId: Long,
        @Header("Authorization") token: String
    ): List<PersonDTO>

    @POST("households/{hid}/people")
    suspend fun addPerson(
        @Path("hid") householdId: Long,
        @Body request: CreatePersonRequest,
        @Header("Authorization") token: String
    ): CreatePersonResponse

    @PATCH("people/{id}")
    suspend fun updatePerson(
        @Path("id") id: Long,
        @Body request: UpdatePersonRequest,
        @Header("Authorization") token: String
    ): PersonDTO

    @DELETE("people/{id}")
    suspend fun deletePerson(
        @Path("id") id: Long,
        @Header("Authorization") token: String
    ): Response<Unit>

    @GET("templates")
    suspend fun getTemplates(
        @Header("Authorization") token: String
    ): List<TemplateDTO>

    @POST("templates")
    suspend fun createTemplate(
        @Body request: CreateTemplateRequest,
        @Header("Authorization") token: String
    ): TemplateDTO

    @PATCH("templates/{id}")
    suspend fun updateTemplate(
        @Path("id") id: Long,
        @Body request: UpdateTemplateRequest,
        @Header("Authorization") token: String
    ): TemplateDTO

    @DELETE("templates/{id}")
    suspend fun deleteTemplate(
        @Path("id") id: Long,
        @Header("Authorization") token: String
    ): Response<Unit>

    @GET("tasks")
    suspend fun getPendingTasks(
        @Header("Authorization") token: String
    ): List<TaskDTO>

    @GET("tasks/history")
    suspend fun getHistory(
        @Query("days") days: Int,
        @Header("Authorization") token: String
    ): List<TaskDTO>

    @POST("tasks/{id}/complete")
    suspend fun completeTask(
        @Path("id") id: Long,
        @Body request: CompleteTaskRequest,
        @Header("Authorization") token: String
    ): CompleteTaskResponse

    @POST("tasks/{id}/skip")
    suspend fun skipTask(
        @Path("id") id: Long,
        @Header("Authorization") token: String
    ): CompleteTaskResponse

    @PATCH("tasks/{id}")
    suspend fun updateTask(
        @Path("id") id: Long,
        @Body request: UpdateTaskRequest,
        @Header("Authorization") token: String
    ): GenericResponse

    @POST("households/{hid}/regenerate-invite")
    suspend fun regenerateInvite(
        @Path("hid") householdId: Long,
        @Header("Authorization") token: String
    ): InviteRegenResponse
}
