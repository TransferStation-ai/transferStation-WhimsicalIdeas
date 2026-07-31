#include "gl_platform.h"
#include "glad/gl.h"

uint32_t (GL_API* glad_glCreateShader)(GLenum type) = nullptr;
void (GL_API* glad_glShaderSource)(uint32_t shader, GLsizei count, const GLchar** string, const GLint* length) = nullptr;
void (GL_API* glad_glCompileShader)(uint32_t shader) = nullptr;
void (GL_API* glad_glGetShaderiv)(uint32_t shader, GLenum pname, GLint* params) = nullptr;
void (GL_API* glad_glGetShaderInfoLog)(uint32_t shader, GLsizei bufSize, GLsizei* length, GLchar* infoLog) = nullptr;
void (GL_API* glad_glDeleteShader)(uint32_t shader) = nullptr;
uint32_t (GL_API* glad_glCreateProgram)(void) = nullptr;
void (GL_API* glad_glAttachShader)(uint32_t program, uint32_t shader) = nullptr;
void (GL_API* glad_glLinkProgram)(uint32_t program) = nullptr;
void (GL_API* glad_glGetProgramiv)(uint32_t program, GLenum pname, GLint* params) = nullptr;
void (GL_API* glad_glGetProgramInfoLog)(uint32_t program, GLsizei bufSize, GLsizei* length, GLchar* infoLog) = nullptr;
void (GL_API* glad_glDeleteProgram)(uint32_t program) = nullptr;
GLint (GL_API* glad_glGetUniformLocation)(uint32_t program, const GLchar* name) = nullptr;

static void* loadGLProc(const char* name) {
    return glPlatformLoadProc(name);
}

int gladLoadGL(void) {
    glad_glCreateShader = (uint32_t (GL_API*)(GLenum))loadGLProc("glCreateShader");
    glad_glShaderSource = (void (GL_API*)(uint32_t, GLsizei, const GLchar**, const GLint*))loadGLProc("glShaderSource");
    glad_glCompileShader = (void (GL_API*)(uint32_t))loadGLProc("glCompileShader");
    glad_glGetShaderiv = (void (GL_API*)(uint32_t, GLenum, GLint*))loadGLProc("glGetShaderiv");
    glad_glGetShaderInfoLog = (void (GL_API*)(uint32_t, GLsizei, GLsizei*, GLchar*))loadGLProc("glGetShaderInfoLog");
    glad_glDeleteShader = (void (GL_API*)(uint32_t))loadGLProc("glDeleteShader");
    glad_glCreateProgram = (uint32_t (GL_API*)())loadGLProc("glCreateProgram");
    glad_glAttachShader = (void (GL_API*)(uint32_t, uint32_t))loadGLProc("glAttachShader");
    glad_glLinkProgram = (void (GL_API*)(uint32_t))loadGLProc("glLinkProgram");
    glad_glGetProgramiv = (void (GL_API*)(uint32_t, GLenum, GLint*))loadGLProc("glGetProgramiv");
    glad_glGetProgramInfoLog = (void (GL_API*)(uint32_t, GLsizei, GLsizei*, GLchar*))loadGLProc("glGetProgramInfoLog");
    glad_glDeleteProgram = (void (GL_API*)(uint32_t))loadGLProc("glDeleteProgram");
    glad_glGetUniformLocation = (GLint (GL_API*)(uint32_t, const GLchar*))loadGLProc("glGetUniformLocation");

    if (!glad_glCreateShader || !glad_glCreateProgram || !glad_glGetUniformLocation) return 0;
    return 1;
}
