package com.example.jetsonapp

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.jetsonapp.recorder.Recorder
import com.example.jetsonapp.utils.CameraUtil.extractFunctionName
import com.example.jetsonapp.utils.WeatherRepository
import com.example.jetsonapp.whisperengine.IWhisperEngine
import com.example.jetsonapp.whisperengine.WhisperEngine
import com.google.ai.edge.localagents.core.proto.Content
import com.google.ai.edge.localagents.core.proto.FunctionDeclaration
import com.google.ai.edge.localagents.core.proto.Part
import com.google.ai.edge.localagents.core.proto.Schema
import com.google.ai.edge.localagents.core.proto.Tool
import com.google.ai.edge.localagents.core.proto.Type
import com.google.ai.edge.localagents.fc.GemmaFormatter
import com.google.ai.edge.localagents.fc.GenerativeModel
import com.google.ai.edge.localagents.fc.HammerFormatter
import com.google.ai.edge.localagents.fc.LlmInferenceBackend
import com.google.ai.edge.localagents.fc.ModelFormatterOptions
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.Backend
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mlkit.nl.languageid.LanguageIdentification
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class JetsonViewModel @Inject constructor(
    application: Application,
    private val weatherRepository: WeatherRepository
) :
    AndroidViewModel(application), TextToSpeech.OnInitListener {

    private val context = application
    private var mediaPlayer: MediaPlayer? = null
    private val whisperEngine: IWhisperEngine = WhisperEngine(context)
    private val recorder: Recorder = Recorder(context)
    private val outputFileWav = File(application.filesDir, RECORDING_FILE_WAV)
    private val languageIdentifier = LanguageIdentification.getClient()
    private lateinit var textToSpeech: TextToSpeech
    private var transcribedText = ""
    private val _userPrompt = MutableStateFlow("")
    private val userPrompt = _userPrompt.asStateFlow()
    private fun updateUserPrompt(newValue: String) {
        _userPrompt.value = newValue
    }

    private val _vlmResult = MutableStateFlow("")
    val vlmResult = _vlmResult.asStateFlow()
    private fun updateVlmResult(newValue: String) {
        _vlmResult.value = newValue
    }

    private val _jetsonIsWorking = MutableStateFlow(false)
    val jetsonIsWorking = _jetsonIsWorking.asStateFlow()
    private fun updateJetsonIsWorking(newValue: Boolean) {
        _jetsonIsWorking.value = newValue
    }

    private val _microphoneIsRecording = MutableStateFlow(false)
    val microphoneIsRecording = _microphoneIsRecording.asStateFlow()
    fun updateMicrophoneIsRecording(newValue: Boolean) {
        _microphoneIsRecording.value = newValue
    }

    private val _cameraFunctionTriggered = MutableStateFlow(false)
    val cameraFunctionTriggered = _cameraFunctionTriggered.asStateFlow()
    fun updateCameraFunctionTriggered(newValue: Boolean) {
        _cameraFunctionTriggered.value = newValue
    }

    private val _phoneGalleryTriggered = MutableStateFlow(false)
    val phoneGalleryTriggered = _phoneGalleryTriggered.asStateFlow()
    fun updatePhoneGalleryTriggered(newValue: Boolean) {
        _phoneGalleryTriggered.value = newValue
    }

    private var generativeModel: GenerativeModel? = null
    private var session: LlmInferenceSession? = null

    private fun initialize() {
        updateJetsonIsWorking(true)
        // Initialize generativeModel and session here
        viewModelScope.launch(Dispatchers.IO) {
            generativeModel = createGenerativeModel()
            session = createSession(context)
            updateJetsonIsWorking(false)
        }
    }

    init {
        whisperEngine.initialize(MODEL_PATH, getAssetFilePath(context = context), false)
        recorder.setFilePath(getFilePath(context = context))

        initialize()
    }

    fun startRecordingWav() {
        recorder.start()
    }

    fun stopRecordingWav() {
        recorder.stop()
        updateVlmResult("")
        updateJetsonIsWorking(true)

        try {
            viewModelScope.launch(Dispatchers.IO) {
                // Offline speech to text
                transcribedText = whisperEngine.transcribeFile(outputFileWav.absolutePath)
                Log.v("transription", transcribedText.trim())

                updateUserPrompt(transcribedText.trim())
                updateVlmResult(transcribedText.trim())

                // Example from the Google's function calling app
                // https://github.com/google-ai-edge/ai-edge-apis/tree/main/examples/function_calling/healthcare_form_demo
                // Conversion instructions
                // https://github.com/google-ai-edge/ai-edge-torch/tree/main/ai_edge_torch/generative/examples
                // Speech recognition example
                // https://medium.com/@andraz.pajtler/android-speech-to-text-the-missing-guide-part-1-824e2636c45a

                // Extract the model's message from the response.
                val chat = generativeModel?.startChat()
                val response = try {
                    chat?.sendMessage(userPrompt.value)
                } catch (e: com.google.ai.edge.localagents.fc.FunctionCallException) {
                    Log.e("function", "Function call parsing error: ${e.message}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Sorry, I didn't understand that. Please try rephrasing your request.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    updateJetsonIsWorking(false)
                    return@launch
                } catch (e: Exception) {
                    Log.e("function", "Unexpected error: ${e.message}")
                    updateJetsonIsWorking(false)
                    return@launch
                }
                Log.v("function", "Model response: $response")

                if (response != null && response.candidatesCount > 0 && response.getCandidates(0).content.partsList.size > 0) {
                    val message = response.getCandidates(0).content.getParts(0)

                    // If the message contains a function call, execute the function.
                    if (message.hasFunctionCall()) {
                        val functionCall = message.functionCall

                        // Call the appropriate function.
                        when (functionCall.name) {
                            "getCameraImage" -> {
                                Log.v("function", "getCameraImage")
                                _cameraFunctionTriggered.value = true
                                updateJetsonIsWorking(false)
                            }

                            "openPhoneGallery" -> {
                                Log.v("function", "openPhoneGallery")
                                _phoneGalleryTriggered.value = true
                                updateJetsonIsWorking(false)
                            }

                            "getWeather" -> {
                                Log.v("function", "getWeather function called")
                                val city = functionCall.args?.fieldsMap?.get("city")?.stringValue ?: "London"
                                handleWeatherRequest(city)
                            }

                            "getTime" -> {
                                Log.v("function", "getTime function called")
                                handleTimeRequest()
                            }

                            "makeCall" -> {
                                Log.v("function", "makeCall function called")
                                val contactName = functionCall.args?.fieldsMap?.get("contactName")?.stringValue ?: ""
                                handleCallRequest(contactName)
                            }

                            else -> {
                                Log.e("function", "no function to call")
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        "No function to call, say something like \"open the camera\", \"what's the weather\", \"what time is it\", or \"call someone\"",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                updateJetsonIsWorking(false)
                                // throw Exception("Function does not exist:" + functionCall.name)
                            }
                        }
                    } else if (message.hasText()) {
                        Log.v("function_else_if", message.text)
                        Log.v(
                            "function_else_if",
                            extractFunctionName(message.text) ?: "no function"
                        )
                        if (extractFunctionName(message.text) == "getCameraImage") {
                            _cameraFunctionTriggered.value = true
                            updateJetsonIsWorking(false)
                        } else if (extractFunctionName(message.text) == "openPhoneGallery") {
                            _phoneGalleryTriggered.value = true
                            updateJetsonIsWorking(false)
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "No function to call, say something like \"open the camera\", \"what's the weather\", \"what time is it\", or \"call someone\"",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } else {
                    Log.v("function_else_if", "no parts")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "No function to call, say something like \"open the camera\", \"what's the weather\", \"what time is it\", or \"call someone\"",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    updateJetsonIsWorking(false)
                }
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, e.toString())
        } catch (e: IllegalStateException) {
            Log.e(TAG, e.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Model Error: ${e.message}", e)
        } finally {
            // updateJetsonIsWorking(false)
        }
    }

    private suspend fun handleWeatherRequest(city: String) {
        val result = weatherRepository.getWeather(city)
        result.onSuccess { weatherMessage ->
            Log.v("weather", "Weather result: $weatherMessage")
            withContext(Dispatchers.Main) {
                updateVlmResult(transcribedText.trim() + "\n\n" + weatherMessage)
                textToSpeech = TextToSpeech(context, this@JetsonViewModel)
                speakOut(weatherMessage)
            }
        }.onFailure { error ->
            Log.e("weather", "Weather error: ${error.message}")
            withContext(Dispatchers.Main) {
                val errorMessage = "Sorry, I couldn't get the weather information for $city. Please try again."
                updateVlmResult(transcribedText.trim() + "\n\n" + errorMessage)
                textToSpeech = TextToSpeech(context, this@JetsonViewModel)
                speakOut(errorMessage)
            }
        }
        updateJetsonIsWorking(false)
    }

    private suspend fun handleTimeRequest() {
        val result = weatherRepository.getCurrentTime()
        result.onSuccess { timeMessage ->
            Log.v("time", "Time result: $timeMessage")
            withContext(Dispatchers.Main) {
                updateVlmResult(transcribedText.trim() + "\n\n" + timeMessage)
                textToSpeech = TextToSpeech(context, this@JetsonViewModel)
                speakOut(timeMessage)
            }
        }.onFailure { error ->
            Log.e("time", "Time error: ${error.message}")
            withContext(Dispatchers.Main) {
                val errorMessage = "Sorry, I couldn't get the current time. Please try again."
                updateVlmResult(transcribedText.trim() + "\n\n" + errorMessage)
                textToSpeech = TextToSpeech(context, this@JetsonViewModel)
                speakOut(errorMessage)
            }
        }
        updateJetsonIsWorking(false)
    }

    private suspend fun handleCallRequest(contactName: String) {
        try {
            Log.v("call", "Opening phone app to search for: $contactName")
            withContext(Dispatchers.Main) {
                // Try to open Phone app directly
                val phoneIntent = Intent(Intent.ACTION_CALL_BUTTON).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }

                try {
                    context.startActivity(phoneIntent)
                } catch (e: Exception) {
                    // Fallback to dialer
                    val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(dialIntent)
                }

                val confirmMessage = if (contactName.isNotEmpty()) {
                    "Opening phone app to search for $contactName"
                } else {
                    "Opening phone app"
                }
                updateVlmResult(transcribedText.trim() + "\n\n" + confirmMessage)
                textToSpeech = TextToSpeech(context, this@JetsonViewModel)
                speakOut(confirmMessage)
            }
        } catch (e: Exception) {
            Log.e("call", "Error opening phone app: ${e.message}")
            withContext(Dispatchers.Main) {
                val errorMessage = "Sorry, I couldn't open the phone app. Please try again."
                updateVlmResult(transcribedText.trim() + "\n\n" + errorMessage)
                textToSpeech = TextToSpeech(context, this@JetsonViewModel)
                speakOut(errorMessage)
            }
        }
        updateJetsonIsWorking(false)
    }
    private var selectedImage = ""

    fun updateSelectedImage(context: Context, uri: Uri) {
        updateJetsonIsWorking(true)
        selectedImage = try {
            val contentResolver = context.contentResolver

            // Convert Uri to Base64 string
            val base64 = contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } ?: ""

            // Create a bitmap from the Uri and log its width and height
            contentResolver.openInputStream(uri)?.use { bmpStream ->
                val bitmap = BitmapFactory.decodeStream(bmpStream)
                bitmap?.let {
                    Log.d("ImageInfo", "Bitmap width: ${it.width}, height: ${it.height}")
                }
                // VLM procedure
                inferenceVLM(bitmap)
            }

            base64
        } catch (e: Exception) {
            Log.e("ImageUtils", "Error processing image", e)
            ""
        }
    }

    fun convertBitmapToBase64(bitmap: Bitmap) {
        updateJetsonIsWorking(true)
        selectedImage = try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            bitmap.let {
                Log.d("ImageInfo", "Bitmap width: ${it.width}, height: ${it.height}")
            }
            val bytes = outputStream.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }

        // VLM procedure
        inferenceVLM(bitmap)
    }

    private fun inferenceVLM(bitmap: Bitmap) {
        var chunkCounter = 0
        viewModelScope.launch(Dispatchers.IO) {
            val chunkBuffer = StringBuilder()
            try {
                textToSpeech = TextToSpeech(context, this@JetsonViewModel)
                // Convert the input Bitmap object to an MPImage object to run inference
                // Process the bitmap (if needed) using BitmapImageBuilder
                val mpImage = BitmapImageBuilder(bitmap).build()
                session?.addQueryChunk(userPrompt.value + " in 20 words") // Limit if you do not want a vast output.
                session?.addImage(mpImage)

                var stringBuilder = ""
                session?.generateResponseAsync { chunk, done ->
                    updateJetsonIsWorking(false)
                    stringBuilder += chunk
                    // Log.v("image_partial", "$stringBuilder $done")
                    updateVlmResult(transcribedText.trim() + "\n\n" + stringBuilder)

                    // Speak the chunks
                    chunkBuffer.append(chunk)
                    chunkCounter++

                    // Check if 7 chunks have been collected
                    if (chunkCounter == 7) {
                        // Speak out the combined text of the last 7 chunks
                        speakOut(chunkBuffer.toString())
                        Log.v("finished_main", chunkBuffer.toString())

                        // Reset the buffer and the counter for the next group of chunks
                        chunkBuffer.clear()
                        chunkCounter = 0
                    }
                }

                if (chunkBuffer.isNotEmpty()) {
                    speakOut(chunkBuffer.toString())
                    Log.v("finished_main", chunkBuffer.toString())
                }

                session?.close()
            } catch (e: Exception) {
                Log.e("image_exception", e.message.toString())
            }
        }
    }

    private fun createGenerativeModel(): GenerativeModel {
        val getCameraImage = FunctionDeclaration.newBuilder()
            .setName("getCameraImage")
            .setDescription("Function to open the camera")
            .build()

        val openPhoneGallery = FunctionDeclaration.newBuilder()
            .setName("openPhoneGallery")
            .setDescription("Function to open the gallery")
            .build()

        val getWeather = FunctionDeclaration.newBuilder()
            .setName("getWeather")
            .setDescription("Get current weather information for a city")
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties(
                        "city",
                        Schema.newBuilder()
                            .setType(Type.STRING)
                            .setDescription("The name of the city to get weather for")
                            .build()
                    )
                    .addRequired("city")
                    .build()
            )
            .build()

        val getTime = FunctionDeclaration.newBuilder()
            .setName("getTime")
            .setDescription("Get the current time")
            .build()

        val makeCall = FunctionDeclaration.newBuilder()
            .setName("makeCall")
            .setDescription("Make a phone call to a contact")
            .setParameters(
                Schema.newBuilder()
                    .setType(Type.OBJECT)
                    .putProperties(
                        "contactName",
                        Schema.newBuilder()
                            .setType(Type.STRING)
                            .setDescription("The name of the contact to call")
                            .build()
                    )
                    .addRequired("contactName")
                    .build()
            )
            .build()

        val tool = Tool.newBuilder()
            .addFunctionDeclarations(getCameraImage)
            .addFunctionDeclarations(openPhoneGallery)
            .addFunctionDeclarations(getWeather)
            .addFunctionDeclarations(getTime)
            .addFunctionDeclarations(makeCall)
            .build()

        val formatter = HammerFormatter(ModelFormatterOptions.builder().setAddPromptTemplate(true).build())

        val llmInferenceOptions = LlmInferenceOptions.builder()
            .setModelPath("/data/local/tmp/hammer2p1_05b_seb.task")
            .setMaxTokens(1024)
            .apply { setPreferredBackend(Backend.GPU) }
            .build()

        val llmInference = LlmInference.createFromOptions(context, llmInferenceOptions)
        val llmInferenceBackend = LlmInferenceBackend(llmInference, formatter)

        val systemInstruction = Content.newBuilder()
            .setRole("system")
            .addParts(
                Part.newBuilder()
                    .setText("You are a helpful assistant. You can open camera, gallery, get weather, tell time, or make calls when requested.")
            )
            .build()

        val model = GenerativeModel(
            llmInferenceBackend,
            systemInstruction,
            listOf(tool).toMutableList()
        )
        return model
    }

    private fun createSession(context: Context): LlmInferenceSession {
        // Configure inference options and create the inference instance
        val options = LlmInferenceOptions.builder()
            .setModelPath("/data/local/tmp/gemma-3n-E2B-it-int4.task")
            .setMaxTokens(1024)
            .setPreferredBackend(Backend.GPU)
            .setMaxNumImages(1)
            .build()
        val llmInference = LlmInference.createFromOptions(context, options)

        // Configure session options and create the session
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(40) // Default
            .setTopP(0.9f)
            .setTemperature(1.0f)
            .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
            .build()
        return LlmInferenceSession.createFromOptions(llmInference, sessionOptions)
    }

    override fun onCleared() {
        super.onCleared()
        mediaPlayer?.release()
        mediaPlayer = null
        textToSpeech.stop()
        textToSpeech.shutdown()
        languageIdentifier.close()
    }

    companion object {
        private const val MODEL_PATH = "whisper_tiny_en_14.tflite"
        private const val VOCAB_PATH = "filters_vocab_en.bin"
        private const val RECORDING_FILE_WAV = "recording.wav"
        private const val TAG = "JetsonViewModel"
    }

    // Returns file path for vocab .bin file
    private fun getFilePath(assetName: String = RECORDING_FILE_WAV, context: Context): String? {
        val outfile = File(context.filesDir, assetName)
        if (!outfile.exists()) {
            Log.d(TAG, "File not found - " + outfile.absolutePath)
        }
        Log.d(TAG, "Returned asset path: " + outfile.absolutePath)
        return outfile.absolutePath
    }

    private fun getAssetFilePath(assetName: String = VOCAB_PATH, context: Context): String? {
        val file = File(context.cacheDir, assetName)
        if (!file.exists()) {
            try {
                context.assets.open(assetName).use { inputStream ->
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
                return null
            }
        }
        return file.absolutePath
    }

    fun stopGenerating() {
        session?.cancelGenerateResponseAsync()
        updateJetsonIsWorking(false)
    }

    // Function to use for Text-to-Speech.
    private fun speakOut(text: String) {
        val defaultLocale = Locale("en")
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { languageCode ->
                val locale = if (languageCode == "und") defaultLocale else Locale(languageCode)
                textToSpeech.setLanguage(locale)
                // Log.v("available_languages", textToSpeech.availableLanguages.toString())
                textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null, "speech_utterance_id")
            }
            .addOnFailureListener {
                textToSpeech.setLanguage(defaultLocale)
                textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "speech_utterance_id")
            }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // TTS initialization successful
            textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                }

                override fun onDone(utteranceId: String?) {
                    updateJetsonIsWorking(false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    updateJetsonIsWorking(false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(p0: String?) {
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    updateJetsonIsWorking(false)
                }

                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                }
            })
        } else {
            // TTS initialization failed
            Log.e("TTS", "Initialization failed")
        }
    }
}