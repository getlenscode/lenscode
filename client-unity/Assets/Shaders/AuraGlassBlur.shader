// Frosted-glass "blur behind" panel for URP. USS has no backdrop-filter, so
// this quad sits just behind each UI Toolkit panel and supplies the actual
// blur-and-tint backdrop the mockup's CSS glass panels rely on.
Shader "Aura/GlassBlur"
{
    Properties
    {
        _TintColor ("Tint Color (RGBA)", Color) = (0.05, 0.09, 0.14, 0.72)
        _BlurRadius ("Blur Radius (screen px)", Range(0, 12)) = 6
        _EdgeHighlight ("Top Edge Highlight", Range(0, 1)) = 0.12
    }
    SubShader
    {
        Tags { "RenderType" = "Opaque" "RenderPipeline" = "UniversalPipeline" "Queue" = "Transparent" }
        // We manually blend the background sample into our own color, so the
        // actual GPU blend stage just writes the result straight through.
        Blend One Zero
        ZWrite On
        Cull Back

        Pass
        {
            Name "GlassBlur"
            HLSLPROGRAM
            #pragma vertex Vert
            #pragma fragment Frag

            #include "Packages/com.unity.render-pipelines.core/ShaderLibrary/Common.hlsl"
            #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/Core.hlsl"
            #include "Packages/com.unity.render-pipelines.universal/ShaderLibrary/DeclareOpaqueTexture.hlsl"

            CBUFFER_START(UnityPerMaterial)
                float4 _TintColor;
                float _BlurRadius;
                float _EdgeHighlight;
            CBUFFER_END

            struct Attributes
            {
                float4 positionOS : POSITION;
                float2 uv : TEXCOORD0;
            };

            struct Varyings
            {
                float4 positionCS : SV_POSITION;
                float4 screenPos : TEXCOORD0;
                float2 uv : TEXCOORD1;
            };

            Varyings Vert(Attributes IN)
            {
                Varyings OUT;
                VertexPositionInputs vpi = GetVertexPositionInputs(IN.positionOS.xyz);
                OUT.positionCS = vpi.positionCS;
                OUT.screenPos = ComputeScreenPos(vpi.positionCS);
                OUT.uv = IN.uv;
                return OUT;
            }

            half4 Frag(Varyings IN) : SV_Target
            {
                float2 uv = IN.screenPos.xy / IN.screenPos.w;
                float2 texel = _BlurRadius / _ScreenParams.xy;

                // 9-tap box blur approximating a soft frosted look without a
                // separate blur pass.
                half3 sum = 0;
                sum += SampleSceneColor(uv + texel * float2(-1, -1)).rgb;
                sum += SampleSceneColor(uv + texel * float2( 0, -1)).rgb;
                sum += SampleSceneColor(uv + texel * float2( 1, -1)).rgb;
                sum += SampleSceneColor(uv + texel * float2(-1,  0)).rgb;
                sum += SampleSceneColor(uv                        ).rgb;
                sum += SampleSceneColor(uv + texel * float2( 1,  0)).rgb;
                sum += SampleSceneColor(uv + texel * float2(-1,  1)).rgb;
                sum += SampleSceneColor(uv + texel * float2( 0,  1)).rgb;
                sum += SampleSceneColor(uv + texel * float2( 1,  1)).rgb;
                half3 blurred = sum / 9.0;

                half3 col = lerp(blurred, _TintColor.rgb, _TintColor.a);

                // Soft highlight near the top edge, echoing the mockup's
                // inset top-highlight on glass panels.
                half topGlow = smoothstep(0.65, 1.0, IN.uv.y) * _EdgeHighlight;
                col += topGlow;

                return half4(col, 1);
            }
            ENDHLSL
        }
    }
}
