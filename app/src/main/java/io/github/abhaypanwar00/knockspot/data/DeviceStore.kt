package io.github.abhaypanwar00.knockspot.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import io.github.abhaypanwar00.knockspot.core.KnockspotState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

private object StateSerializer : Serializer<KnockspotState> {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    override val defaultValue = KnockspotState()

    override suspend fun readFrom(input: InputStream): KnockspotState = try {
        json.decodeFromString(KnockspotState.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable Knockspot state", e)
    }

    override suspend fun writeTo(t: KnockspotState, output: OutputStream) {
        output.write(json.encodeToString(KnockspotState.serializer(), t).encodeToByteArray())
    }
}

private val Context.stateStore: DataStore<KnockspotState> by dataStore("knockspot.json", StateSerializer)

/** Persists [KnockspotState] as JSON. All changes go through [update], which is atomic. */
class DeviceStore(context: Context) {
    private val store = context.applicationContext.stateStore

    val state: Flow<KnockspotState> = store.data

    suspend fun current(): KnockspotState = store.data.first()

    suspend fun update(transform: (KnockspotState) -> KnockspotState): KnockspotState =
        store.updateData { transform(it) }
}
