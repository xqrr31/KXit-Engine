#ifdef GL_ES
precision mediump float;
#endif
uniform sampler2D u_texture;
uniform vec4 u_fogColor;
uniform float u_fogStart;
uniform float u_fogEnd;
uniform float u_daylight;
varying vec2 v_texCoords;
varying vec4 v_color;
varying float v_distance;
void main() {
    vec4 texel = texture2D(u_texture, v_texCoords) * v_color;
    float fog = clamp((v_distance - u_fogStart) / max(0.001, (u_fogEnd - u_fogStart)), 0.0, 1.0);
    vec3 lit = texel.rgb * u_daylight;
    gl_FragColor = vec4(mix(lit, u_fogColor.rgb, fog * 0.78), texel.a);
}
