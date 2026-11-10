#ifdef GL_ES
precision mediump float;
#endif
attribute vec3 a_position;
attribute vec2 a_texCoord0;
attribute vec4 a_color;
uniform mat4 u_projViewTrans;
uniform vec3 u_cameraPosition;
varying vec2 v_texCoords;
varying vec4 v_color;
varying float v_distance;
void main() {
    vec4 worldPosition = vec4(a_position, 1.0);
    gl_Position = u_projViewTrans * worldPosition;
    v_texCoords = a_texCoord0;
    v_color = a_color;
    v_distance = distance(u_cameraPosition, a_position);
}
