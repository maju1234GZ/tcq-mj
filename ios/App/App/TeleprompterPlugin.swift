import Capacitor
import AVFoundation
import Speech
import Photos
import UIKit
import WebKit

// MARK: - 预览容器视图：随 superview 自动铺满，layoutSubviews 时同步预览层尺寸

private final class PreviewHostView: UIView {
    override func layoutSubviews() {
        super.layoutSubviews()
        layer.sublayers?.forEach { $0.frame = bounds }
    }
}

// MARK: - Teleprompter 插件

@objc(TeleprompterPlugin)
public class TeleprompterPlugin: CAPPlugin, CAPBridgedPlugin, AVCaptureFileOutputRecordingDelegate {

    public let identifier = "TeleprompterPlugin"
    public let jsName = "Teleprompter"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "hasPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startOverlay", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "updateOverlay", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopOverlay", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getOverlayState", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startSpeech", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopSpeech", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startCamera", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopCamera", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopRecording", returnType: CAPPluginReturnPromise)
    ]

    // 相机
    private let sessionQueue = DispatchQueue(label: "com.solo.teleprompter.capture")
    private var captureSession: AVCaptureSession?
    private var movieFileOutput: AVCaptureMovieFileOutput?
    private var previewHostView: UIView?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var pendingRecordCallId: String?

    // 语音识别
    private var speechActive = false
    private var speechSource: String?
    private var systemAccumulatedText = ""
    private var audioEngine: AVAudioEngine?
    private var speechRecognizer: SFSpeechRecognizer?
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?

    // 云端 ASR
    private var urlSession: URLSession?
    private var webSocketTask: URLSessionWebSocketTask?

    deinit {
        captureSession?.stopRunning()
        audioEngine?.stop()
        recognitionTask?.cancel()
        webSocketTask?.cancel(with: .normalClosure, reason: nil)
        urlSession?.invalidateAndCancel()
    }

    // MARK: - 权限

    private var micGranted: Bool {
        if #available(iOS 17.0, *) {
            return AVAudioApplication.shared.recordPermission == .granted
        } else {
            return AVAudioSession.sharedInstance().recordPermission == .granted
        }
    }

    private var cameraGranted: Bool {
        return AVCaptureDevice.authorizationStatus(for: .video) == .authorized
    }

    @objc func hasPermissions(_ call: CAPPluginCall) {
        call.resolve([
            "overlay": true,
            "mic": micGranted,
            "camera": cameraGranted
        ])
    }

    @objc func requestPermissions(_ call: CAPPluginCall) {
        requestMicPermission { [weak self] in
            self?.requestCameraPermission {
                self?.requestSpeechPermission {
                    self?.hasPermissions(call)
                }
            }
        }
    }

    private func requestMicPermission(_ completion: @escaping () -> Void) {
        if #available(iOS 17.0, *) {
            AVAudioApplication.requestRecordPermission { _ in completion() }
        } else {
            AVAudioSession.sharedInstance().requestRecordPermission { _ in completion() }
        }
    }

    private func requestCameraPermission(_ completion: @escaping () -> Void) {
        AVCaptureDevice.requestAccess(for: .video) { _ in completion() }
    }

    private func requestSpeechPermission(_ completion: @escaping () -> Void) {
        SFSpeechRecognizer.requestAuthorization { _ in completion() }
    }

    // MARK: - Android 悬浮窗（iOS 空实现，直接 resolve）

    @objc func startOverlay(_ call: CAPPluginCall) {
        call.resolve()
    }

    @objc func updateOverlay(_ call: CAPPluginCall) {
        call.resolve()
    }

    @objc func stopOverlay(_ call: CAPPluginCall) {
        call.resolve()
    }

    @objc func getOverlayState(_ call: CAPPluginCall) {
        call.resolve(["running": false, "position": 0])
    }

    // MARK: - 相机

    @objc func startCamera(_ call: CAPPluginCall) {
        let position = call.getString("position") ?? "front"
        if captureSession != nil {
            call.resolve()
            return
        }
        guard cameraGranted else {
            call.reject("相机权限未授权")
            return
        }
        sessionQueue.async { [weak self] in
            guard let self = self else {
                call.resolve()
                return
            }
            let desiredPosition: AVCaptureDevice.Position = (position == "back") ? .back : .front
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: desiredPosition)
                    ?? AVCaptureDevice.default(for: .video) else {
                call.reject("找不到可用的摄像头")
                return
            }
            let session = AVCaptureSession()
            session.sessionPreset = .high
            do {
                let videoInput = try AVCaptureDeviceInput(device: device)
                guard session.canAddInput(videoInput) else {
                    call.reject("无法添加视频输入")
                    return
                }
                session.addInput(videoInput)
                // 音频输入（录制带声音的视频）
                if let audioDevice = AVCaptureDevice.default(for: .audio),
                   let audioInput = try? AVCaptureDeviceInput(device: audioDevice),
                   session.canAddInput(audioInput) {
                    session.addInput(audioInput)
                }
                let output = AVCaptureMovieFileOutput()
                guard session.canAddOutput(output) else {
                    call.reject("无法添加录制输出")
                    return
                }
                session.addOutput(output)
                self.captureSession = session
                self.movieFileOutput = output
            } catch {
                call.reject("打开摄像头失败: \(error.localizedDescription)")
                return
            }
            DispatchQueue.main.async { [weak self] in
                guard let self = self, self.captureSession === session else {
                    call.resolve()
                    return
                }
                self.attachPreviewLayer(session: session)
                self.sessionQueue.async { [weak self] in
                    session.startRunning()
                    if self?.captureSession != nil {
                        call.resolve()
                    }
                }
            }
        }
    }

    @objc func stopCamera(_ call: CAPPluginCall) {
        sessionQueue.async { [weak self] in
            guard let self = self else {
                call.resolve()
                return
            }
            if let output = self.movieFileOutput, output.isRecording {
                output.stopRecording()
            }
            if let session = self.captureSession {
                session.stopRunning()
                session.inputs.forEach { session.removeInput($0) }
                session.outputs.forEach { session.removeOutput($0) }
            }
            self.captureSession = nil
            self.movieFileOutput = nil
            DispatchQueue.main.async { [weak self] in
                guard let self = self else {
                    call.resolve()
                    return
                }
                self.previewLayer?.removeFromSuperlayer()
                self.previewLayer = nil
                self.previewHostView?.removeFromSuperview()
                self.previewHostView = nil
                call.resolve()
            }
        }
    }

    private func attachPreviewLayer(session: AVCaptureSession) {
        guard let webView = bridge?.webView, let superview = webView.superview else { return }
        // WebView 透明，让预览层透出来
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear

        let host = PreviewHostView(frame: superview.bounds)
        host.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        host.backgroundColor = .black
        superview.insertSubview(host, at: 0)

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = host.bounds
        host.layer.addSublayer(preview)
        configureVideoOrientation(for: preview.connection)

        self.previewHostView = host
        self.previewLayer = preview
    }

    private func configureVideoOrientation(for connection: AVCaptureConnection?) {
        guard let connection = connection else { return }
        if #available(iOS 17.0, *) {
            if connection.isVideoRotationAngleSupported(90) {
                connection.videoRotationAngle = 90
            }
        } else {
            if connection.isVideoOrientationSupported {
                connection.videoOrientation = .portrait
            }
        }
    }

    // MARK: - 录制

    @objc func startRecording(_ call: CAPPluginCall) {
        guard let output = movieFileOutput else {
            call.reject("相机未启动")
            return
        }
        guard !output.isRecording else {
            call.resolve()
            return
        }
        sessionQueue.async { [weak self] in
            guard let self = self else {
                call.resolve()
                return
            }
            let fileName = "teleprompter_\(UUID().uuidString).mov"
            let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
            self.configureVideoOrientation(for: output.connection(with: .video))
            output.startRecording(to: fileURL, recordingDelegate: self)
            call.resolve()
        }
    }

    @objc func stopRecording(_ call: CAPPluginCall) {
        guard let output = movieFileOutput, output.isRecording else {
            call.resolve(["saved": false])
            return
        }
        call.keepAlive = true
        bridge?.saveCall(call)
        pendingRecordCallId = call.callbackId
        sessionQueue.async { [weak self] in
            self?.movieFileOutput?.stopRecording()
        }
    }

    // AVCaptureFileOutputRecordingDelegate
    public func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL,
                           from connections: [AVCaptureConnection], error: Error?) {
        if let error = error {
            finishRecording(saved: false, message: error.localizedDescription, fileURL: outputFileURL)
            return
        }
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { [weak self] status in
            guard status == .authorized || status == .limited else {
                self?.finishRecording(saved: false, message: "相册写入权限未授权", fileURL: outputFileURL)
                return
            }
            PHPhotoLibrary.shared().performChanges({
                PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: outputFileURL)
            }) { success, saveError in
                self?.finishRecording(saved: success,
                                      message: success ? nil : (saveError?.localizedDescription ?? "保存相册失败"),
                                      fileURL: outputFileURL)
            }
        }
    }

    private func finishRecording(saved: Bool, message: String?, fileURL: URL) {
        try? FileManager.default.removeItem(at: fileURL)
        var eventData: [String: Any] = ["action": saved ? "saved" : "failed"]
        if let message = message {
            eventData["message"] = message
        }
        notifyListeners("recordEvent", data: eventData)
        if let callId = pendingRecordCallId, let savedCall = bridge?.savedCall(withID: callId) {
            savedCall.resolve(["saved": saved])
            bridge?.releaseCall(savedCall)
        }
        pendingRecordCallId = nil
    }

    // MARK: - 语音识别（入口）

    @objc func startSpeech(_ call: CAPPluginCall) {
        let source = call.getString("source") ?? "system"
        let lang = call.getString("lang") ?? "zh-CN"
        stopSpeechInternal()
        if source == "cloud" {
            startCloudSpeech(call: call, lang: lang, serverUrl: call.getString("serverUrl"))
        } else {
            startSystemSpeech(call: call, lang: lang)
        }
    }

    @objc func stopSpeech(_ call: CAPPluginCall) {
        stopSpeechInternal()
        call.resolve()
    }

    private func stopSpeechInternal() {
        speechActive = false
        teardownSystemSpeechComponents()
        teardownCloudSpeechComponents()
        speechSource = nil
        systemAccumulatedText = ""
        speechRecognizer = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func configureRecordAudioSession() throws {
        let audioSession = AVAudioSession.sharedInstance()
        try audioSession.setCategory(.record, mode: .measurement, options: .duckOthers)
        try audioSession.setActive(true, options: .notifyOthersOnDeactivation)
    }

    // MARK: - 系统语音识别

    private func startSystemSpeech(call: CAPPluginCall, lang: String) {
        guard micGranted else {
            call.reject("麦克风权限未授权")
            return
        }
        guard SFSpeechRecognizer.authorizationStatus() == .authorized else {
            call.reject("语音识别权限未授权")
            return
        }
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: lang)), recognizer.isAvailable else {
            call.reject("系统语音识别不可用")
            return
        }
        do {
            try configureRecordAudioSession()
        } catch {
            call.reject("音频会话配置失败: \(error.localizedDescription)")
            return
        }
        speechActive = true
        speechSource = "system"
        systemAccumulatedText = ""
        speechRecognizer = recognizer
        startSystemRecognitionTask()
        call.resolve()
    }

    private func startSystemRecognitionTask() {
        guard speechActive, speechSource == "system", let recognizer = speechRecognizer else { return }

        let engine = AVAudioEngine()
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true

        let inputNode = engine.inputNode
        let inputFormat = inputNode.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0 else {
            return
        }
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] buffer, _ in
            self?.recognitionRequest?.append(buffer)
        }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            inputNode.removeTap(onBus: 0)
            return
        }
        self.audioEngine = engine
        self.recognitionRequest = request
        self.recognitionTask = recognizer.recognitionTask(with: request) { [weak self] result, error in
            guard let self = self else { return }
            var finished = false
            if let result = result {
                let piece = result.bestTranscription.formattedString
                let cumulative = self.systemAccumulatedText + piece
                let isFinal = result.isFinal
                self.notifyListeners("speech", data: ["text": cumulative, "isFinal": isFinal])
                if isFinal {
                    self.systemAccumulatedText = cumulative
                    finished = true
                }
            }
            if error != nil {
                finished = true
            }
            if finished {
                // 单次识别会话结束（或达到系统时限），自动重启以持续识别
                self.teardownSystemSpeechComponents()
                if self.speechActive, self.speechSource == "system" {
                    DispatchQueue.global().asyncAfter(deadline: .now() + 0.3) { [weak self] in
                        self?.startSystemRecognitionTask()
                    }
                }
            }
        }
    }

    private func teardownSystemSpeechComponents() {
        if let engine = audioEngine {
            engine.stop()
            engine.inputNode.removeTap(onBus: 0)
        }
        audioEngine = nil
        recognitionRequest?.endAudio()
        recognitionRequest = nil
        let task = recognitionTask
        recognitionTask = nil
        task?.cancel()
    }

    // MARK: - 云端 ASR（WebSocket + PCM16 16kHz 单声道）

    private func startCloudSpeech(call: CAPPluginCall, lang: String, serverUrl: String?) {
        guard micGranted else {
            call.reject("麦克风权限未授权")
            return
        }
        guard let urlString = serverUrl, let url = URL(string: urlString) else {
            call.reject("缺少有效的 serverUrl")
            return
        }
        do {
            try configureRecordAudioSession()
        } catch {
            call.reject("音频会话配置失败: \(error.localizedDescription)")
            return
        }
        speechActive = true
        speechSource = "cloud"

        let session = URLSession(configuration: .default)
        let task = session.webSocketTask(with: url)
        self.urlSession = session
        self.webSocketTask = task
        task.resume()

        // 首帧：config
        let config: [String: Any] = ["type": "config", "lang": lang, "sampleRate": 16000]
        if let data = try? JSONSerialization.data(withJSONObject: config),
           let text = String(data: data, encoding: .utf8) {
            task.send(.string(text)) { _ in }
        }
        receiveCloudMessage()
        startCloudAudioTap()
        call.resolve()
    }

    private func startCloudAudioTap() {
        guard speechActive, speechSource == "cloud" else { return }
        guard let outputFormat = AVAudioFormat(commonFormat: .pcmFormatInt16,
                                               sampleRate: 16000,
                                               channels: 1,
                                               interleaved: true) else { return }
        let engine = AVAudioEngine()
        let inputNode = engine.inputNode
        let inputFormat = inputNode.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0 else { return }
        guard let converter = AVAudioConverter(from: inputFormat, to: outputFormat) else { return }

        inputNode.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] buffer, _ in
            self?.sendConvertedAudio(buffer: buffer, converter: converter, outputFormat: outputFormat)
        }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            inputNode.removeTap(onBus: 0)
            return
        }
        self.audioEngine = engine
    }

    private func sendConvertedAudio(buffer: AVAudioPCMBuffer,
                                    converter: AVAudioConverter,
                                    outputFormat: AVAudioFormat) {
        guard speechActive, speechSource == "cloud", let task = webSocketTask else { return }
        let ratio = 16000.0 / buffer.format.sampleRate
        let capacity = AVAudioFrameCount((Double(buffer.frameLength) * ratio).rounded(.up)) + 256
        guard let outBuffer = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: capacity) else { return }
        var error: NSError?
        var consumed = false
        converter.convert(to: outBuffer, error: &error) { _, outStatus in
            if consumed {
                outStatus.pointee = .endOfStream
                return nil
            }
            consumed = true
            outStatus.pointee = .haveData
            return buffer
        }
        guard error == nil, outBuffer.frameLength > 0, let channelData = outBuffer.int16ChannelData else { return }
        let byteCount = Int(outBuffer.frameLength) * MemoryLayout<Int16>.size
        let data = Data(bytes: channelData[0], count: byteCount)
        task.send(.data(data)) { _ in }
    }

    private func receiveCloudMessage() {
        webSocketTask?.receive { [weak self] result in
            guard let self = self, self.speechActive, self.speechSource == "cloud" else { return }
            switch result {
            case .success(let message):
                if case .string(let text) = message {
                    self.handleCloudText(text)
                }
                self.receiveCloudMessage()
            case .failure:
                break
            }
        }
    }

    private func handleCloudText(_ text: String) {
        guard let data = text.data(using: .utf8),
              let dict = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let type = dict["type"] as? String else { return }
        switch type {
        case "partial", "final":
            let recognized = dict["text"] as? String ?? ""
            notifyListeners("speech", data: ["text": recognized, "isFinal": type == "final"])
        default:
            break
        }
    }

    private func teardownCloudSpeechComponents() {
        if let task = webSocketTask {
            if let data = try? JSONSerialization.data(withJSONObject: ["type": "end"]),
               let text = String(data: data, encoding: .utf8) {
                task.send(.string(text)) { _ in }
            }
            task.cancel(with: .normalClosure, reason: nil)
        }
        webSocketTask = nil
        urlSession?.invalidateAndCancel()
        urlSession = nil
    }
}
