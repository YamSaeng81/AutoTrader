import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  // 2026-08-20: ignoreBuildErrors 를 껐다. 켜져 있는 동안 타입 오류 8건이 그대로
  // 배포됐고, 그중에는 Badge 의 variant 26종 중 13종이 스타일 없이 렌더되던 것과
  // useMutation 에 존재하지도 않는 select 옵션을 넘기던 것이 있었다.
  // 다시 켜야 할 상황이라면 그 오류를 고치는 쪽이 맞다.
  typescript: {
    ignoreBuildErrors: false,
  },
  async rewrites() {
    const backendUrl = process.env.INTERNAL_BACKEND_URL || "http://localhost:8080";
    return [
      {
        source: "/api/v1/:path*",
        destination: `${backendUrl}/api/v1/:path*`,
      },
    ];
  },
};

export default nextConfig;
