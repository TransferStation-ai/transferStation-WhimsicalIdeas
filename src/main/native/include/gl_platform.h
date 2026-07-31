#ifndef GL_PLATFORM_H
#define GL_PLATFORM_H

#include <cstdint>
#include <cstddef>

#if defined(_WIN32)
  #define WIN32_LEAN_AND_MEAN
  #define NOMINMAX
  #include <windows.h>
  #define GL_API __stdcall
  inline void* glPlatformLoadProc(const char* name) {
      void* p = (void*)wglGetProcAddress(name);
      if (!p) {
          p = (void*)GetProcAddress(GetModuleHandleA("opengl32.dll"), name);
      }
      return p;
  }
#elif defined(__linux__)
  #include <GL/glx.h>
  #define GL_API
  inline void* glPlatformLoadProc(const char* name) {
      return (void*)glXGetProcAddress((const GLubyte*)name);
  }
#elif defined(__ANDROID__)
  #include <EGL/egl.h>
  #define GL_API
  inline void* glPlatformLoadProc(const char* name) {
      return (void*)eglGetProcAddress(name);
  }
#endif

#endif
