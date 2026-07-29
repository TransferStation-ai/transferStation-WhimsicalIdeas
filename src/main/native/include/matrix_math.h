#ifndef MATRIX_MATH_H
#define MATRIX_MATH_H

#include <cstdint>
#include <cmath>

struct Matrix4x4 {
    float m[16];

    static Matrix4x4 identity() {
        Matrix4x4 mat;
        for (int i = 0; i < 16; ++i) mat.m[i] = 0.0f;
        mat.m[0] = mat.m[5] = mat.m[10] = mat.m[15] = 1.0f;
        return mat;
    }

    static Matrix4x4 from3x4(const float src[12]) {
        Matrix4x4 mat;
        mat.m[0]  = src[0];
        mat.m[1]  = src[4];
        mat.m[2]  = src[8];
        mat.m[3]  = 0.0f;
        mat.m[4]  = src[1];
        mat.m[5]  = src[5];
        mat.m[6]  = src[9];
        mat.m[7]  = 0.0f;
        mat.m[8]  = src[2];
        mat.m[9]  = src[6];
        mat.m[10] = src[10];
        mat.m[11] = 0.0f;
        mat.m[12] = src[3];
        mat.m[13] = src[7];
        mat.m[14] = src[11];
        mat.m[15] = 1.0f;
        return mat;
    }

    Matrix4x4 inverse() const {
        Matrix4x4 inv;
        inv.m[0]  = m[0];  inv.m[1]  = m[4];  inv.m[2]  = m[8];  inv.m[3]  = 0.0f;
        inv.m[4]  = m[1];  inv.m[5]  = m[5];  inv.m[6]  = m[9];  inv.m[7]  = 0.0f;
        inv.m[8]  = m[2];  inv.m[9]  = m[6];  inv.m[10] = m[10]; inv.m[11] = 0.0f;
        inv.m[12] = -(m[0]*m[12] + m[1]*m[13] + m[2]*m[14]);
        inv.m[13] = -(m[4]*m[12] + m[5]*m[13] + m[6]*m[14]);
        inv.m[14] = -(m[8]*m[12] + m[9]*m[13] + m[10]*m[14]);
        inv.m[15] = 1.0f;
        return inv;
    }

    static Matrix4x4 multiply(const Matrix4x4& a, const Matrix4x4& b) {
        Matrix4x4 r;
        for (int col = 0; col < 4; ++col) {
            for (int row = 0; row < 4; ++row) {
                float sum = 0.0f;
                for (int k = 0; k < 4; ++k) {
                    sum += a.m[k * 4 + row] * b.m[col * 4 + k];
                }
                r.m[col * 4 + row] = sum;
            }
        }
        return r;
    }

    void transformPoint(const float in[3], float out[3]) const {
        float x = in[0], y = in[1], z = in[2];
        out[0] = m[0]*x + m[4]*y + m[8]*z  + m[12];
        out[1] = m[1]*x + m[5]*y + m[9]*z  + m[13];
        out[2] = m[2]*x + m[6]*y + m[10]*z + m[14];
    }

    void transformNormal(const float in[3], float out[3]) const {
        float x = in[0], y = in[1], z = in[2];
        out[0] = m[0]*x + m[4]*y + m[8]*z;
        out[1] = m[1]*x + m[5]*y + m[9]*z;
        out[2] = m[2]*x + m[6]*y + m[10]*z;
    }
};

static_assert(sizeof(Matrix4x4) == 16 * sizeof(float), "Matrix4x4 must be 64 bytes");

#endif // MATRIX_MATH_H
