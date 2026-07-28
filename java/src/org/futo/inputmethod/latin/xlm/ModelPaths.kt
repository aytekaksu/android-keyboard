package org.futo.inputmethod.latin.xlm

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.Keep
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.MutableSharedFlow
import org.futo.inputmethod.annotations.ExternallyReferenced
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.utils.AtomicFileInstaller
import org.futo.inputmethod.latin.utils.JniUtils
import java.io.File


val BASE_MODEL_RESOURCE = R.raw.ml4_q6_k
val BASE_MODEL_NAME = "ml4_q6_k"
val DEPRECATED_MODEL_NAME = "ml4_1_f16_meta_fixed"

val MODEL_OPTION_KEY = SettingsKey(
    stringSetPreferencesKey("lmModelsByLanguage"),
    setOf("en:$BASE_MODEL_NAME")
)

@Keep
@ExternallyReferenced
data class ModelInfo(
    val name: String,
    val description: String,
    val author: String,
    val license: String,
    val features: List<String>,
    val languages: List<String>,
    val tokenizer_type: String,
    val finetune_count: Int,
    val path: String
) {
    fun toLoader(): ModelInfoLoader {
        return ModelInfoLoader(File(path), name)
    }

    fun isUnsupported(): Boolean {
        return features.isEmpty() || tokenizer_type == "None" || languages.isEmpty()
    }
}

class ModelInfoLoader(
    val path: File,
    val name: String,
) {
    fun loadDetails(): ModelInfo? {
        JniUtils.loadNativeLibrary()
        return loadNative(path.absolutePath)
    }

    external fun loadNative(path: String): ModelInfo?
}

object ModelPaths {
    val modelOptionsUpdated = MutableSharedFlow<Unit>(replay = 0)
    private var validatedBaseModel: Pair<Long, Long>? = null
    private var baseModelResourceSize: Long? = null

    fun exportModel(context: Context, uri: Uri, file: File) {
        context.contentResolver.openOutputStream(uri)!!.use { output ->
            file.inputStream().use { it.copyTo(output) }
        }
    }


    suspend fun signalReloadModels() {
        modelOptionsUpdated.emit(Unit)
    }

    suspend fun updateModelOption(
        context: Context,
        key: String,
        value: File,
        notifyListeners: Boolean = true,
    ) {
        if(!value.absolutePath.startsWith(context.filesDir.absolutePath)) {
            throw IllegalArgumentException("Model path ${value.absolutePath} does not start with filesDir path ${context.filesDir.absolutePath}")
        }

        val options = context.getSetting(MODEL_OPTION_KEY).filter {
            it.split(":", limit = 2)[0] != key
        }.toMutableSet()

        options.add("$key:${value.nameWithoutExtension}")

        context.setSetting(MODEL_OPTION_KEY, options)

        if (notifyListeners) signalReloadModels()
    }

    suspend fun getModelOptions(context: Context): Map<String, ModelInfoLoader> {
        ensureDefaultModelExists(context)
        val modelDirectory = getModelDirectory(context)
        val options = context.getSetting(MODEL_OPTION_KEY)

        val modelOptionsByLanguage = hashMapOf<String, ModelInfoLoader>()
        options.forEach { option ->
            val separator = option.indexOf(':')
            if (separator <= 0 || separator == option.lastIndex) {
                Log.w("ModelPaths", "Ignoring malformed model option: $option")
                return@forEach
            }
            val language = option.substring(0, separator)
            var modelName = option.substring(separator + 1)

            if(modelName == DEPRECATED_MODEL_NAME) {
                modelName = BASE_MODEL_NAME
                updateModelOption(context, language, File(modelDirectory, BASE_MODEL_NAME))
            }

            // TODO: This assumes the extension is .gguf
            val modelFile = File(modelDirectory, "$modelName.gguf")
            if(modelFile.exists()) {
                modelOptionsByLanguage[language] = ModelInfoLoader(modelFile, modelName)
            } else {
                Log.e("ModelPaths", "Option for language $language set to $modelName, but could not find ${modelFile.absolutePath}")
            }
        }

        return modelOptionsByLanguage
    }

    fun getModelDirectory(context: Context): File {
        val modelDirectory = File(context.filesDir, "transformer-models")

        if(!modelDirectory.isDirectory){
            modelDirectory.mkdir()
        }

        return modelDirectory
    }

    private fun isValidBaseModel(file: File, minimumSize: Long): Boolean {
        if (!file.isFile || file.length() < minimumSize) return false
        val fingerprint = file.length() to file.lastModified()
        if (validatedBaseModel == fingerprint) return true
        val hasMagic = file.inputStream().use { input ->
            val bytes = ByteArray(4)
            input.read(bytes) == bytes.size &&
                bytes[0] == 'G'.code.toByte() &&
                bytes[1] == 'G'.code.toByte() &&
                bytes[2] == 'U'.code.toByte() &&
                bytes[3] == 'F'.code.toByte()
        }
        if (!hasMagic) return false
        val valid = runCatching {
            ModelInfoLoader(file, BASE_MODEL_NAME).loadDetails() != null
        }.getOrDefault(false)
        if (valid) validatedBaseModel = fingerprint
        return valid
    }

    @Synchronized
    fun ensureDefaultModelExists(context: Context) {
        val directory = getModelDirectory(context)


        val oldFile = File(directory, "$DEPRECATED_MODEL_NAME.gguf")
        if(oldFile.isFile) oldFile.delete()

        val tgtFile = File(directory, "$BASE_MODEL_NAME.gguf")
        val resourceSize = baseModelResourceSize ?: context.resources
            .openRawResource(BASE_MODEL_RESOURCE)
            .use { it.available().toLong() }
            .also { baseModelResourceSize = it }
        AtomicFileInstaller.install(
            target = tgtFile,
            expectedSize = resourceSize,
            source = { context.resources.openRawResource(BASE_MODEL_RESOURCE) },
            isValid = { isValidBaseModel(it, resourceSize) },
        )
    }

    fun shouldFileBeIncludedInExport(file: File): Boolean {
        if(file.name == "$BASE_MODEL_NAME.gguf") {
            val loader = ModelInfoLoader(file, file.nameWithoutExtension)
            val info = loader.loadDetails()
            if(info == null) return false
            return info.finetune_count > 0
        } else {
            return true
        }
    }

    fun getModels(context: Context): List<ModelInfoLoader> {
        ensureDefaultModelExists(context)

        return getModelDirectory(context).listFiles()
            ?.filter { it.isFile && it.extension == "gguf" }
            ?.map {
            ModelInfoLoader(
                path = it,
                name = it.nameWithoutExtension
            )
        } ?: listOf()
    }
}
