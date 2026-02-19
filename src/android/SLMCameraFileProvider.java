package com.slm.camera;

import androidx.core.content.FileProvider;

/**
 * Custom FileProvider subclass to avoid manifest merger conflicts
 * when multiple plugins declare FileProvider.
 */
public class SLMCameraFileProvider extends FileProvider {
}
