var exec = require('cordova/exec');

var SLMCamera = {

    /**
     * Abre la camara para tomar una foto.
     * @param {Object} options - { quality, targetWidth, targetHeight, encodingType, correctOrientation, saveToGallery, returnType }
     * Retorna: { imageData, width, height, format }
     */
    takePicture: function (options, success, error) {
        exec(success, error, 'SLMCamera', 'takePicture', [options || {}]);
    },

    /**
     * Abre la galeria para seleccionar una foto.
     * @param {Object} options - { quality, targetWidth, targetHeight, encodingType, correctOrientation, returnType }
     * Retorna: { imageData, width, height, format }
     */
    chooseFromGallery: function (options, success, error) {
        exec(success, error, 'SLMCamera', 'chooseFromGallery', [options || {}]);
    },

    /**
     * Limpia archivos temporales de la camara.
     */
    cleanup: function (success, error) {
        exec(success, error, 'SLMCamera', 'cleanup', []);
    }
};

module.exports = SLMCamera;
