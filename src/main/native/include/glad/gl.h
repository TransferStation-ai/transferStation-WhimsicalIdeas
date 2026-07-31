#ifndef GLAD_GL_H
#define GLAD_GL_H

#include <cstdint>

#ifdef __cplusplus
#include "gl_platform.h"
extern "C" {
#endif

/* OpenGL types */
typedef unsigned int GLenum;
typedef unsigned char GLboolean;
typedef unsigned int GLbitfield;
typedef signed char GLbyte;
typedef short GLshort;
typedef int GLint;
typedef int GLsizei;
typedef unsigned char GLubyte;
typedef unsigned short GLushort;
typedef unsigned int GLuint;
typedef float GLfloat;
typedef float GLclampf;
typedef double GLdouble;
typedef double GLclampd;
typedef char GLchar;

/* OpenGL 1.0 constants */
#define GL_TRUE 1
#define GL_FALSE 0

/* OpenGL 2.0 shader constants */
#define GL_VERTEX_SHADER 0x8B31
#define GL_FRAGMENT_SHADER 0x8B30
#define GL_COMPILE_STATUS 0x8B81
#define GL_LINK_STATUS 0x8B82

/* GL function pointer declarations */
extern uint32_t (GL_API* glad_glCreateShader)(GLenum type);
extern void (GL_API* glad_glShaderSource)(uint32_t shader, GLsizei count, const GLchar** string, const GLint* length);
extern void (GL_API* glad_glCompileShader)(uint32_t shader);
extern void (GL_API* glad_glGetShaderiv)(uint32_t shader, GLenum pname, GLint* params);
extern void (GL_API* glad_glGetShaderInfoLog)(uint32_t shader, GLsizei bufSize, GLsizei* length, GLchar* infoLog);
extern void (GL_API* glad_glDeleteShader)(uint32_t shader);
extern uint32_t (GL_API* glad_glCreateProgram)(void);
extern void (GL_API* glad_glAttachShader)(uint32_t program, uint32_t shader);
extern void (GL_API* glad_glLinkProgram)(uint32_t program);
extern void (GL_API* glad_glGetProgramiv)(uint32_t program, GLenum pname, GLint* params);
extern void (GL_API* glad_glGetProgramInfoLog)(uint32_t program, GLsizei bufSize, GLsizei* length, GLchar* infoLog);
extern void (GL_API* glad_glDeleteProgram)(uint32_t program);
extern GLint (GL_API* glad_glGetUniformLocation)(uint32_t program, const GLchar* name);

/* Load all GL function pointers */
int gladLoadGL(void);

#ifdef __cplusplus
}
#endif

#endif /* GLAD_GL_H */