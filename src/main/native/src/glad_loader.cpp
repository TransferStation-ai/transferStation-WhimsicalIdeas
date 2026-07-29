#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include "glad/gl.h"

/* Define the function pointers */
uint32_t (__stdcall* glad_glCreateShader)(GLenum type) = nullptr;
void (__stdcall* glad_glShaderSource)(uint32_t shader, GLsizei count, const GLchar** string, const GLint* length) = nullptr;
void (__stdcall* glad_glCompileShader)(uint32_t shader) = nullptr;
void (__stdcall* glad_glGetShaderiv)(uint32_t shader, GLenum pname, GLint* params) = nullptr;
void (__stdcall* glad_glGetShaderInfoLog)(uint32_t shader, GLsizei bufSize, GLsizei* length, GLchar* infoLog) = nullptr;
void (__stdcall* glad_glDeleteShader)(uint32_t shader) = nullptr;
uint32_t (__stdcall* glad_glCreateProgram)(void) = nullptr;
void (__stdcall* glad_glAttachShader)(uint32_t program, uint32_t shader) = nullptr;
void (__stdcall* glad_glLinkProgram)(uint32_t program) = nullptr;
void (__stdcall* glad_glGetProgramiv)(uint32_t program, GLenum pname, GLint* params) = nullptr;
void (__stdcall* glad_glGetProgramInfoLog)(uint32_t program, GLsizei bufSize, GLsizei* length, GLchar* infoLog) = nullptr;
void (__stdcall* glad_glDeleteProgram)(uint32_t program) = nullptr;
GLint (__stdcall* glad_glGetUniformLocation)(uint32_t program, const GLchar* name) = nullptr;

static void* loadGLProc(const char* name) {
    void* p = (void*)wglGetProcAddress(name);
    if (!p) {
        p = (void*)GetProcAddress(GetModuleHandleA("opengl32.dll"), name);
    }
    return p;
}

int gladLoadGL(void) {
    glad_glCreateShader = (uint32_t (__stdcall*)(GLenum))loadGLProc("glCreateShader");
    glad_glShaderSource = (void (__stdcall*)(uint32_t, GLsizei, const GLchar**, const GLint*))loadGLProc("glShaderSource");
    glad_glCompileShader = (void (__stdcall*)(uint32_t))loadGLProc("glCompileShader");
    glad_glGetShaderiv = (void (__stdcall*)(uint32_t, GLenum, GLint*))loadGLProc("glGetShaderiv");
    glad_glGetShaderInfoLog = (void (__stdcall*)(uint32_t, GLsizei, GLsizei*, GLchar*))loadGLProc("glGetShaderInfoLog");
    glad_glDeleteShader = (void (__stdcall*)(uint32_t))loadGLProc("glDeleteShader");
    glad_glCreateProgram = (uint32_t (__stdcall*)())loadGLProc("glCreateProgram");
    glad_glAttachShader = (void (__stdcall*)(uint32_t, uint32_t))loadGLProc("glAttachShader");
    glad_glLinkProgram = (void (__stdcall*)(uint32_t))loadGLProc("glLinkProgram");
    glad_glGetProgramiv = (void (__stdcall*)(uint32_t, GLenum, GLint*))loadGLProc("glGetProgramiv");
    glad_glGetProgramInfoLog = (void (__stdcall*)(uint32_t, GLsizei, GLsizei*, GLchar*))loadGLProc("glGetProgramInfoLog");
    glad_glDeleteProgram = (void (__stdcall*)(uint32_t))loadGLProc("glDeleteProgram");
    glad_glGetUniformLocation = (GLint (__stdcall*)(uint32_t, const GLchar*))loadGLProc("glGetUniformLocation");

    if (!glad_glCreateShader || !glad_glCreateProgram || !glad_glGetUniformLocation) return 0;
    return 1;
}