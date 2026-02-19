import UIKit
import AVFoundation
import Photos
import PhotosUI

@objc(SLMCamera) class SLMCamera: CDVPlugin, UIImagePickerControllerDelegate, UINavigationControllerDelegate {

    private var currentCallbackId: String?
    private var currentOptions: [String: Any] = [:]

    // MARK: - takePicture

    @objc(takePicture:)
    func takePicture(command: CDVInvokedUrlCommand) {
        currentCallbackId = command.callbackId
        currentOptions = command.arguments[0] as? [String: Any] ?? [:]

        DispatchQueue.main.async {
            guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
                let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Camara no disponible")
                self.commandDelegate.send(result, callbackId: command.callbackId)
                return
            }

            let picker = UIImagePickerController()
            picker.sourceType = .camera
            picker.delegate = self
            picker.allowsEditing = false
            self.viewController.present(picker, animated: true)
        }
    }

    // MARK: - chooseFromGallery

    @objc(chooseFromGallery:)
    func chooseFromGallery(command: CDVInvokedUrlCommand) {
        currentCallbackId = command.callbackId
        currentOptions = command.arguments[0] as? [String: Any] ?? [:]

        DispatchQueue.main.async {
            if #available(iOS 14.0, *) {
                var config = PHPickerConfiguration()
                config.selectionLimit = 1
                config.filter = .images
                let picker = PHPickerViewController(configuration: config)
                picker.delegate = self
                self.viewController.present(picker, animated: true)
            } else {
                let picker = UIImagePickerController()
                picker.sourceType = .photoLibrary
                picker.delegate = self
                picker.allowsEditing = false
                self.viewController.present(picker, animated: true)
            }
        }
    }

    // MARK: - cleanup

    @objc(cleanup:)
    func cleanup(command: CDVInvokedUrlCommand) {
        commandDelegate.run {
            let tmpDir = NSTemporaryDirectory()
            let fileManager = FileManager.default
            var cleaned = 0

            if let files = try? fileManager.contentsOfDirectory(atPath: tmpDir) {
                for file in files {
                    if file.hasPrefix("slm_camera_") {
                        try? fileManager.removeItem(atPath: tmpDir + file)
                        cleaned += 1
                    }
                }
            }

            let info: [String: Any] = ["cleaned": cleaned]
            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: info)
            self.commandDelegate.send(result, callbackId: command.callbackId)
        }
    }

    // MARK: - UIImagePickerControllerDelegate

    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        picker.dismiss(animated: true) {
            guard let image = info[.originalImage] as? UIImage else {
                if let callbackId = self.currentCallbackId {
                    let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "No se pudo obtener la imagen")
                    self.commandDelegate.send(result, callbackId: callbackId)
                }
                return
            }

            self.processImage(image)

            // Guardar en galeria si es necesario
            if self.currentOptions["saveToGallery"] as? Bool == true {
                UIImageWriteToSavedPhotosAlbum(image, nil, nil, nil)
            }
        }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)
        if let callbackId = currentCallbackId {
            let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Usuario cancelo la captura")
            commandDelegate.send(result, callbackId: callbackId)
        }
    }

    // MARK: - Image Processing

    private func processImage(_ originalImage: UIImage) {
        commandDelegate.run {
            var image = originalImage

            // Corregir orientacion
            let correctOrientation = self.currentOptions["correctOrientation"] as? Bool ?? true
            if correctOrientation {
                image = self.fixOrientation(image)
            }

            // Resize
            let targetWidth = self.currentOptions["targetWidth"] as? Int ?? 0
            let targetHeight = self.currentOptions["targetHeight"] as? Int ?? 0
            if targetWidth > 0 || targetHeight > 0 {
                image = self.resizeImage(image, targetWidth: targetWidth, targetHeight: targetHeight)
            }

            // Encoding
            let quality = self.currentOptions["quality"] as? Int ?? 85
            let encodingType = self.currentOptions["encodingType"] as? Int ?? 0
            let returnType = self.currentOptions["returnType"] as? String ?? "base64"

            var imageData: Data?
            var format = "jpeg"

            if encodingType == 1 {
                imageData = image.pngData()
                format = "png"
            } else {
                imageData = image.jpegData(compressionQuality: CGFloat(quality) / 100.0)
                format = "jpeg"
            }

            guard let data = imageData, let callbackId = self.currentCallbackId else {
                if let callbackId = self.currentCallbackId {
                    let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Error procesando imagen")
                    self.commandDelegate.send(result, callbackId: callbackId)
                }
                return
            }

            var response: [String: Any] = [
                "width": Int(image.size.width),
                "height": Int(image.size.height),
                "format": format
            ]

            if returnType == "fileURI" {
                let fileName = "slm_camera_\(Int(Date().timeIntervalSince1970 * 1000)).\(format == "png" ? "png" : "jpg")"
                let filePath = NSTemporaryDirectory() + fileName
                try? data.write(to: URL(fileURLWithPath: filePath))
                response["imageData"] = "file://" + filePath
            } else {
                response["imageData"] = data.base64EncodedString()
            }

            let result = CDVPluginResult(status: CDVCommandStatus_OK, messageAs: response)
            self.commandDelegate.send(result, callbackId: callbackId)
        }
    }

    private func fixOrientation(_ image: UIImage) -> UIImage {
        if image.imageOrientation == .up { return image }

        UIGraphicsBeginImageContextWithOptions(image.size, false, image.scale)
        image.draw(in: CGRect(origin: .zero, size: image.size))
        let normalized = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()

        return normalized ?? image
    }

    private func resizeImage(_ image: UIImage, targetWidth: Int, targetHeight: Int) -> UIImage {
        let originalWidth = image.size.width
        let originalHeight = image.size.height

        var newWidth = CGFloat(targetWidth)
        var newHeight = CGFloat(targetHeight)

        if targetWidth > 0 && targetHeight > 0 {
            newWidth = CGFloat(targetWidth)
            newHeight = CGFloat(targetHeight)
        } else if targetWidth > 0 {
            let ratio = CGFloat(targetWidth) / originalWidth
            newWidth = CGFloat(targetWidth)
            newHeight = originalHeight * ratio
        } else if targetHeight > 0 {
            let ratio = CGFloat(targetHeight) / originalHeight
            newHeight = CGFloat(targetHeight)
            newWidth = originalWidth * ratio
        } else {
            return image
        }

        UIGraphicsBeginImageContextWithOptions(CGSize(width: newWidth, height: newHeight), false, 1.0)
        image.draw(in: CGRect(x: 0, y: 0, width: newWidth, height: newHeight))
        let resized = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()

        return resized ?? image
    }
}

// MARK: - PHPickerViewControllerDelegate (iOS 14+)

@available(iOS 14.0, *)
extension SLMCamera: PHPickerViewControllerDelegate {

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)

        guard let provider = results.first?.itemProvider, provider.canLoadObject(ofClass: UIImage.self) else {
            if let callbackId = currentCallbackId {
                let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "No se selecciono ninguna imagen")
                commandDelegate.send(result, callbackId: callbackId)
            }
            return
        }

        provider.loadObject(ofClass: UIImage.self) { [weak self] object, error in
            guard let self = self, let image = object as? UIImage else {
                if let callbackId = self?.currentCallbackId {
                    let result = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAs: "Error cargando imagen: \(error?.localizedDescription ?? "desconocido")")
                    self?.commandDelegate.send(result, callbackId: callbackId)
                }
                return
            }

            self.processImage(image)
        }
    }
}
