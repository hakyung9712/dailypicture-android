/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.techtown.dailypicture.fragments

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.Camera
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import androidx.camera.core.CameraX
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysisConfig
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.CaptureMode
import androidx.camera.core.ImageCapture.Metadata
import androidx.camera.core.ImageCaptureConfig
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.PreviewConfig
import androidx.navigation.Navigation
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
/*import com.android.example.cameraxbasic.KEY_EVENT_ACTION
import com.android.example.cameraxbasic.KEY_EVENT_EXTRA
import com.android.example.cameraxbasic.CameraActivity
import com.android.example.cameraxbasic.R
import com.android.example.cameraxbasic.utils.ANIMATION_FAST_MILLIS
import com.android.example.cameraxbasic.utils.ANIMATION_SLOW_MILLIS
import com.android.example.cameraxbasic.utils.AutoFitPreviewBuilder
import com.android.example.cameraxbasic.utils.simulateClick*/
import org.techtown.dailypicture.utils.ANIMATION_FAST_MILLIS
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.techtown.dailypicture.CameraActivity
import org.techtown.dailypicture.KEY_EVENT_ACTION
import org.techtown.dailypicture.KEY_EVENT_EXTRA
import org.techtown.dailypicture.R
//import org.techtown.dailypicture.frgments.CameraFragmentDirections
import org.techtown.dailypicture.utils.ANIMATION_FAST_MILLIS
import org.techtown.dailypicture.utils.ANIMATION_SLOW_MILLIS
import org.techtown.dailypicture.utils.AutoFitPreviewBuilder
import org.techtown.dailypicture.utils.simulateClick
import java.io.File
import java.lang.Exception
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Helper type alias used for analysis use case callbacks */
typealias LumaListener = (luma: Double) -> Unit

/**
 * 이 응용 프로그램의 주요 조각. 카메라를 포함한 모든 카메라 작동을 구현합니다:
 * - Viewfinder
 * - Photo taking
 * - Image analysis
 */
class CameraFragment : Fragment() {

    private lateinit var container: ConstraintLayout
    private lateinit var viewFinder: TextureView
    private lateinit var outputDirectory: File
    private lateinit var broadcastManager: LocalBroadcastManager

    private var displayId = -1
    private var lensFacing = CameraX.LensFacing.BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null

    /** 볼륨을 낮추면 사진찍기 */
    private val volumeDownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(KEY_EVENT_EXTRA, KeyEvent.KEYCODE_UNKNOWN)) {
                // When the volume down button is pressed, simulate a shutter button click
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    val shutter = container
                            .findViewById<ImageButton>(R.id.camera_capture_button)
                    shutter.simulateClick()
                }
            }
        }
    }

    /** [DisplayManager]의 내부 참조 */
    private lateinit var displayManager: DisplayManager

    /**
     * 구성을 트리거하지 않는 방향 변경을위한 디스플레이 리스너가 필요합니다.
          * 변경 (예 : 매니페스트 또는 180도 구성 변경을 재정의하기로 선택한 경우)
          * 방향이 바뀝니다.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                preview?.setTargetRotation(view.display.rotation)
                imageCapture?.setTargetRotation(view.display.rotation)
                imageAnalyzer?.setTargetRotation(view.display.rotation)
            }
        } ?: Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 이것을 유지 조각으로 표시하여 구성 변경시 수명주기가 다시 시작되지 않습니다.
        retainInstance = true
    }

    override fun onResume() {
        super.onResume()
        // 사용자가 권한을 제거했을 수 있으므로 모든 권한이 여전히 존재하는지 확인하십시오.
        //  앱이 일시 중지 상태 인 동안
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                    CameraFragmentDirections.actionCameraToPermissions())

        }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // 브로드 캐스트 리시버 및 리스너 등록 해제
        broadcastManager.unregisterReceiver(volumeDownReceiver)
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.fragment_camera, container, false)

    private fun setGalleryThumbnail(file: File) {
        // 갤러리 축소판을 보유한보기의 참조
        val thumbnail = container.findViewById<ImageButton>(R.id.photo_view_button)

        // 뷰의 스레드에서 작업을 실행하십시오.
        thumbnail.post {

            // Remove thumbnail padding
            thumbnail.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())

            // Load thumbnail into circular button using Glide
            Glide.with(thumbnail)
                    .load(file)
                    .apply(RequestOptions.circleCropTransform())
                    .into(thumbnail)

        }
    }

    /** 사진을 촬영하고 디스크에 저장 한 후 트리거되는 콜백 정의 */
    private val imageSavedListener = object : ImageCapture.OnImageSavedListener {
        override fun onError(
                error: ImageCapture.ImageCaptureError, message: String, exc: Throwable?) {
            Log.e(TAG, "Photo capture failed: $message")
            exc?.printStackTrace()
        }

        override fun onImageSaved(photoFile: File) {
            Log.d(TAG, "Photo capture succeeded: ${photoFile.absolutePath}")


            // API 레벨 23+ API를 사용하여 포 그라운드 Drawable 만 변경할 수 있습니다
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {


                //최근에 찍은 사진으로 갤러리 썸네일 업데이트
                setGalleryThumbnail(photoFile)

            }


            //API를 실행하는 장치의 경우 암시 적 브로드 캐스트는 무시됩니다.
            // 레벨> = 24이므로 24 세 이상 만 타겟팅하면이 문장을 제거 할 수 있습니다
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                requireActivity().sendBroadcast(
                        Intent(Camera.ACTION_NEW_PICTURE, Uri.fromFile(photoFile)))
            }

            // 선택한 폴더가 외부 미디어 디렉토리 인 경우 필요하지 않습니다
            // 그렇지 않으면 다른 앱은 우리가 아닌 이상 이미지에 액세스 할 수 없습니다
            // [MediaScannerConnection]을 사용하여 스캔
            val mimeType = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(photoFile.extension)
            MediaScannerConnection.scanFile(
                    context, arrayOf(photoFile.absolutePath), arrayOf(mimeType), null)
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                CameraFragmentDirections.actionCameraToGallery(outputDirectory.absolutePath))
        }
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        container = view as ConstraintLayout
        viewFinder = container.findViewById(R.id.view_finder)
        broadcastManager = LocalBroadcastManager.getInstance(view.context)

        // 주요 활동에서 이벤트를 수신 할 인 텐트 필터 설정
        val filter = IntentFilter().apply { addAction(KEY_EVENT_ACTION) }
        broadcastManager.registerReceiver(volumeDownReceiver, filter)

        //장치 방향이 바뀔 때마다 레이아웃을 다시 계산
        displayManager = viewFinder.context
                .getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)

        // 출력 디렉토리 결정
        outputDirectory = CameraActivity.getOutputDirectory(requireContext())

        // 보기가 올바르게 배치 될 때까지 기다리십시오.
        viewFinder.post {
            // 이 뷰가 연결된 디스플레이를 추적하십시오
            displayId = viewFinder.display.displayId

            // UI 컨트롤 구축 및 모든 카메라 사용 사례 바인딩
            updateCameraUi()
            bindCameraUseCases()

            // 백그라운드에서 갤러리 미리보기 이미지를 위해 찍은 최신 사진을로드합니다 (있는 경우).
            lifecycleScope.launch(Dispatchers.IO) {
                outputDirectory.listFiles { file ->
                    EXTENSION_WHITELIST.contains(file.extension.toUpperCase())
                }.sorted().reversed().firstOrNull()?.let { setGalleryThumbnail(it) }
            }
        }
    }

    /** 미리보기, 캡처 및 분석 사용 사례 선언 및 바인딩 */
    private fun bindCameraUseCases() {

        // 전체 화면 해상도로 카메라를 설정하는 데 사용되는 화면 메트릭 가져 오기
        val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
        val screenAspectRatio = Rational(metrics.widthPixels, metrics.heightPixels)
        Log.d(TAG, "Screen metrics: ${metrics.widthPixels} x ${metrics.heightPixels}")

        // 카메라 미리보기를 표시하도록 뷰 파인더 사용 사례 설정
        val viewFinderConfig = PreviewConfig.Builder().apply {
            setLensFacing(lensFacing)
            // CameraX가 사용 사례를 최적화 할 수 있도록 종횡비를 요청하지만 해상도는 없습니다.
            setTargetAspectRatio(screenAspectRatio)
            // 초기 대상 회전을 설정합니다. 회전이 변경되면 다시 호출해야합니다.
            //이 사용 사례의 수명주기 동안
            setTargetRotation(viewFinder.display.rotation)
        }.build()

        // 자동 맞춤 미리보기 빌더를 사용하여 크기 및 방향 변경을 자동으로 처리
        preview = AutoFitPreviewBuilder.build(viewFinderConfig, viewFinder)

        // 사용자가 사진을 찍을 수 있도록 캡처 유스 케이스 설정
        val imageCaptureConfig = ImageCaptureConfig.Builder().apply {
            setLensFacing(lensFacing)
            setCaptureMode(CaptureMode.MIN_LATENCY)
            // 프리뷰 설정과 일치하도록 종횡비를 요청하지만 해상도는 요청하지 않지만
            // 요청 된 캡처 모드에 가장 적합한 특정 해상도를 위해 CameraX 최적화
            setTargetAspectRatio(screenAspectRatio)
            // 초기 목표 로테이션을 설정합니다. 로테이션이 바뀌면 다시 호출해야합니다
            // during the lifecycle of this use case
            setTargetRotation(viewFinder.display.rotation)
        }.build()

        imageCapture = ImageCapture(imageCaptureConfig)

        // 평균 픽셀 휘도를 실시간으로 계산하는 설정 이미지 분석 파이프 라인
        val analyzerConfig = ImageAnalysisConfig.Builder().apply {
            setLensFacing(lensFacing)
            // 분석에서 우리는 * 모든 * 이미지를 분석하는 것보다 최신 이미지를 더 중요하게 생각합니다
            setImageReaderMode(ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE)
            // 초기 목표 로테이션을 설정합니다. 로테이션이 바뀌면 다시 호출해야합니다
            // during the lifecycle of this use case
            setTargetRotation(viewFinder.display.rotation)
        }.build()

        imageAnalyzer = ImageAnalysis(analyzerConfig).apply {
            analyzer = LuminosityAnalyzer { luma ->
                // 분석기에서 반환 된 값은 연결된 리스너로 전달됩니다.
                // 이미지 분석 결과를 여기에 기록합니다. 대신 유용한 것을 수행해야합니다!
                val fps = (analyzer as LuminosityAnalyzer).framesPerSecond
                Log.d(TAG, "Average luminosity: $luma. " +
                        "Frames per second: ${"%.01f".format(fps)}")
            }
        }

        // 동일한 수명주기 소유자를 사용하여 선언 된 구성을 CameraX에 적용
        CameraX.bindToLifecycle(
                viewLifecycleOwner, preview, imageCapture, imageAnalyzer)
    }

    /** 구성을 변경할 때마다 호출되는 카메라 UI 컨트롤을 다시 그리는 데 사용되는 방법s */
    @SuppressLint("RestrictedApi")
    private fun updateCameraUi() {

        //있는 경우 이전 UI 제거
        container.findViewById<ConstraintLayout>(R.id.camera_ui_container)?.let {
            container.removeView(it)
        }

        // 카메라 제어를위한 모든 UI가 포함 된 새 뷰를 팽창시킵니다
        val controls = View.inflate(requireContext(), R.layout.camera_ui_container, container)

        // 사진을 캡처하는 데 사용되는 버튼의 리스너
        controls.findViewById<ImageButton>(R.id.camera_capture_button).setOnClickListener {
            // 수정 가능한 이미지 캡처 사용 사례에 대한 안정적인 참조
            imageCapture?.let { imageCapture ->

                // 이미지를 담을 출력 파일 만들기
                val photoFile = createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)

                // 이미지 캡처 메타 데이터 설정
                val metadata = Metadata().apply {
                    //전면 카메라 사용시 거울 이미지
                    isReversedHorizontal = lensFacing == CameraX.LensFacing.FRONT
                }

                // 사진 촬영 후 트리거되는 설정 이미지 캡처 리스너
                imageCapture.takePicture(photoFile, imageSavedListener, metadata)

                // API 레벨 23+ API를 사용하여 포 그라운드 Drawable 만 변경할 수 있습니다
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                    // 사진이 촬영되었음을 나타내는 플래시 애니메이션 표시
                    container.postDelayed({
                        container.foreground = ColorDrawable(Color.WHITE)
                        container.postDelayed(
                                { container.foreground = null }, ANIMATION_FAST_MILLIS)
                    }, ANIMATION_SLOW_MILLIS)
                }
                /*Navigation.findNavController(requireActivity(), R.id.gallery_fragment).navigate(
                    CameraFragmentDirections.actionCameraToGallery(outputDirectory.absolutePath))*/
            }
        }

        // Listener for button used to switch cameras
        controls.findViewById<ImageButton>(R.id.camera_switch_button).setOnClickListener {
            lensFacing = if (CameraX.LensFacing.FRONT == lensFacing) {
                CameraX.LensFacing.BACK
            } else {
                CameraX.LensFacing.FRONT
            }
            try {
                // 이 방향으로 카메라를 쿼리 할 수있는 경우에만 사용 사례 바인딩
                CameraX.getCameraWithLensFacing(lensFacing)

                // 모든 사용 사례를 바인드 해제하고 새 렌즈 대면 구성으로 다시 바인드하십시오.
                CameraX.unbindAll()
                bindCameraUseCases()
            } catch (exc: Exception) {
                // Do nothing
            }
        }

        // Listener for button used to view last photo
        /*controls.findViewById<ImageButton>(R.id.photo_view_button).setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                    CameraFragmentDirections.actionCameraToGallery(outputDirectory.absolutePath))
        }*/
    }


    /**
     * 우리의 사용자 정의 이미지 분석 클래스.
          *
          * <p> 우리가 원하는 것은 원하는 연산으로 함수 'analyze'를 무시하는 것입니다. 이리,
          * YUV 프레임의 Y 평면을보고 이미지의 평균 광도를 계산합니다.
     */
    private class LuminosityAnalyzer(listener: LumaListener? = null) : ImageAnalysis.Analyzer {
        private val frameRateWindow = 8
        private val frameTimestamps = ArrayDeque<Long>(5)
        private val listeners = ArrayList<LumaListener>().apply { listener?.let { add(it) } }
        private var lastAnalyzedTimestamp = 0L
        var framesPerSecond: Double = -1.0
            private set

        /**
         * 계산 된 각 루마와 함께 호출 될 리스너를 추가하는 데 사용됩니다.
         */
        fun onFrameAnalyzed(listener: LumaListener) = listeners.add(listener)

        /**
         * 이미지 평면 버퍼에서 바이트 배열을 추출하는 데 사용되는 도우미 확장 기능
         */
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        /**
         * Analyzes an image to produce a result.
         *
         * <p>The caller is responsible for ensuring this analysis method can be executed quickly
         * enough to prevent stalls in the image acquisition pipeline. Otherwise, newly available
         * images will not be acquired and analyzed.
         *
         * <p>The image passed to this method becomes invalid after this method returns. The caller
         * should not store external references to this image, as these references will become
         * invalid.
         *
         * @param image image being analyzed VERY IMPORTANT: do not close the image, it will be
         * automatically closed after this method returns
         * @return the image analysis result
         */
        override fun analyze(image: ImageProxy, rotationDegrees: Int) {
            // If there are no listeners attached, we don't need to perform analysis
            if (listeners.isEmpty()) return

            // Keep track of frames analyzed
            frameTimestamps.push(System.currentTimeMillis())

            // Compute the FPS using a moving average
            while (frameTimestamps.size >= frameRateWindow) frameTimestamps.removeLast()
            framesPerSecond = 1.0 / ((frameTimestamps.peekFirst() -
                    frameTimestamps.peekLast())  / frameTimestamps.size.toDouble()) * 1000.0

            // Calculate the average luma no more often than every second
            if (frameTimestamps.first - lastAnalyzedTimestamp >= TimeUnit.SECONDS.toMillis(1)) {
                lastAnalyzedTimestamp = frameTimestamps.first

                // Since format in ImageAnalysis is YUV, image.planes[0] contains the luminance
                //  plane
                val buffer = image.planes[0].buffer

                // Extract image data from callback object
                val data = buffer.toByteArray()

                // Convert the data into an array of pixel values ranging 0-255
                val pixels = data.map { it.toInt() and 0xFF }

                // Compute average luminance for the image
                val luma = pixels.average()

                // Call all listeners with new value
                listeners.forEach { it(luma) }
            }
        }
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"

        /** Helper function used to create a timestamped file */
        private fun createFile(baseFolder: File, format: String, extension: String) =
                File(baseFolder, SimpleDateFormat(format, Locale.US)
                        .format(System.currentTimeMillis()) + extension)
    }
}