package com.growbuddy.app.data.remote

import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/** Retrofit binding of docs/API.md. Base URL is `<server>/api/v1/`. */
interface GrowBuddyApi {
    @GET("health")
    suspend fun health(): Health

    @GET("status")
    suspend fun status(): ServerStatus

    @GET("environment/current")
    suspend fun environment(): EnvironmentCurrent

    @GET("sensors")
    suspend fun sensors(@Query("include_retired") includeRetired: Boolean = false): List<Sensor>

    @GET("sensors/discovered")
    suspend fun discoveredSensors(): List<DiscoveredSensor>

    @POST("sensors")
    suspend fun adoptSensor(@Body body: AdoptRequest): SensorDetail

    @GET("sensors/{id}")
    suspend fun sensor(@Path("id") id: Long): SensorDetail

    @PATCH("sensors/{id}")
    suspend fun patchSensor(@Path("id") id: Long, @Body body: SensorPatch): SensorDetail

    @DELETE("sensors/{id}")
    suspend fun retireSensor(@Path("id") id: Long): Response<Unit>

    @PUT("sensors/{id}/ranges")
    suspend fun putRanges(@Path("id") id: Long, @Body body: SensorRanges): SensorRanges

    @POST("sensors/{id}/ranges/from-phases")
    suspend fun rangesFromPhases(@Path("id") id: Long, @Body body: PhasePresetRequest): SensorRanges

    @GET("phase-ranges")
    suspend fun phaseRanges(): List<PhaseRange>

    @POST("alerts/test")
    suspend fun testNotification(): Response<Unit>

    @GET("push-endpoint")
    suspend fun pushEndpoint(): PushEndpointBody

    @PUT("push-endpoint")
    suspend fun putPushEndpoint(@Body body: PushEndpointBody): PushEndpointBody

    @DELETE("push-endpoint")
    suspend fun deletePushEndpoint(): Response<Unit>

    @GET("plants")
    suspend fun plants(@Query("include_archived") includeArchived: Boolean = false): List<Plant>

    @POST("plants")
    suspend fun createPlant(@Body body: PlantCreate): Plant

    @GET("strains")
    suspend fun strains(
        @Query("q") query: String? = null,
        @Query("category") category: String? = null,
        @Query("vendor") vendor: String? = null,
        @Query("limit") limit: Int = 1000,
    ): StrainPage

    @POST("strains")
    suspend fun createStrain(@Body body: StrainBody): Strain

    /** Body is a JsonObject so that explicit nulls (clear a field) survive serialization. */
    @PATCH("strains/{id}")
    suspend fun patchStrain(@Path("id") id: Long, @Body changes: JsonObject): Strain

    @DELETE("strains/{id}")
    suspend fun deleteStrain(@Path("id") id: Long): Response<Unit>

    @GET("strains/facets")
    suspend fun strainFacets(): Map<String, List<String>>

    @GET("plants/{id}")
    suspend fun plant(@Path("id") id: String): Plant

    /** Body is a JsonObject so that explicit nulls (clear a field) survive serialization. */
    @PATCH("plants/{id}")
    suspend fun patchPlant(@Path("id") id: String, @Body changes: JsonObject): Plant

    @DELETE("plants/{id}")
    suspend fun deletePlant(@Path("id") id: String): Response<Unit>

    @GET("plants/{id}/logs")
    suspend fun plantLogs(@Path("id") id: String, @Query("limit") limit: Int = 50, @Query("before") before: Long? = null): LogPage

    @GET("logs")
    suspend fun logs(@Query("limit") limit: Int = 50, @Query("before") before: Long? = null, @Query("type") type: String? = null): LogPage

    @GET("logs/{id}")
    suspend fun log(@Path("id") id: String): LogEntry

    @POST("logs")
    suspend fun createLog(@Body body: LogCreate): LogEntry

    @PATCH("logs/{id}")
    suspend fun patchLog(@Path("id") id: String, @Body changes: JsonObject): LogEntry

    @DELETE("logs/{id}")
    suspend fun deleteLog(@Path("id") id: String): Response<Unit>

    @Multipart
    @POST("photos")
    suspend fun uploadPhoto(@Part("id") id: RequestBody, @Part file: MultipartBody.Part): Photo

    @GET("nutrient-lines")
    suspend fun nutrientLines(): List<NutrientLine>

    @GET("feeding-schedules")
    suspend fun feedingSchedules(): List<FeedingScheduleSummary>

    @GET("feeding-schedules/{id}")
    suspend fun feedingSchedule(@Path("id") id: Long): FeedingSchedule

    @POST("feeding-schedules")
    suspend fun createFeedingSchedule(@Body body: FeedingScheduleBody): FeedingSchedule

    @PUT("feeding-schedules/{id}")
    suspend fun putFeedingSchedule(@Path("id") id: Long, @Body body: FeedingScheduleBody): FeedingSchedule

    @DELETE("feeding-schedules/{id}")
    suspend fun deleteFeedingSchedule(@Path("id") id: Long): Response<Unit>

    @GET("alerts/active")
    suspend fun activeAlerts(): List<Alert>

    @GET("alerts")
    suspend fun alertHistory(@Query("limit") limit: Int = 50, @Query("before") before: Long? = null): AlertPage

    @GET("notification-languages")
    suspend fun notificationLanguages(): List<NotificationLanguage>

    @GET("alert-settings")
    suspend fun alertSettings(): AlertSettings

    @PUT("alert-settings")
    suspend fun putAlertSettings(@Body body: AlertSettings): AlertSettings

    @GET("events")
    suspend fun events(@Query("limit") limit: Int = 50, @Query("before") before: Long? = null, @Query("level") level: String? = null): EventPage

    @GET("snapshots")
    suspend fun snapshots(
        @Query("from") from: Long? = null,
        @Query("limit") limit: Int = 1000,
        @Query("before") before: Long? = null,
    ): SnapshotPage

    @POST("camera/capture")
    suspend fun captureSnapshot(): Snapshot

    @GET("readings")
    suspend fun readings(
        @Query("sensor_id") sensorId: Long,
        @Query("from") from: Long,
        @Query("to") to: Long,
        @Query("max_points") maxPoints: Int = 500,
        @Query("resolution") resolution: String = "auto",
    ): ReadingSeries
}
