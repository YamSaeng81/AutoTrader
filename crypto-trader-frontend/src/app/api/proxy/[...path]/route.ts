import { NextRequest, NextResponse } from 'next/server';

/**
 * 백엔드 API 프록시 — API_TOKEN을 서버사이드에서만 주입
 *
 * 클라이언트는 /api/proxy/v1/... 로 요청 → 이 Route Handler가
 * INTERNAL_BACKEND_URL + Authorization 헤더를 붙여 백엔드로 전달
 *
 * NEXT_PUBLIC_API_TOKEN을 제거하고 API_TOKEN(서버사이드 전용)으로 전환하면
 * 토큰이 클라이언트 JS 번들에 포함되지 않는다.
 */

const BACKEND_URL = process.env.INTERNAL_BACKEND_URL || 'http://localhost:8080';
const API_TOKEN = process.env.API_TOKEN;

async function proxyRequest(request: NextRequest, pathSegments: string[]): Promise<NextResponse> {
    const backendPath = '/' + pathSegments.join('/');
    const search = request.nextUrl.search;
    const targetUrl = `${BACKEND_URL}${backendPath}${search}`;

    const headers = new Headers(request.headers);
    headers.delete('host');

    if (API_TOKEN) {
        headers.set('Authorization', `Bearer ${API_TOKEN}`);
    }

    let body: BodyInit | null = null;
    const method = request.method;
    if (method !== 'GET' && method !== 'HEAD') {
        body = await request.blob();
    }

    const backendResponse = await fetch(targetUrl, {
        method,
        headers,
        body,
        // @ts-expect-error — Node.js fetch duplex 옵션
        duplex: 'half',
    });

    const responseHeaders = new Headers(backendResponse.headers);
    // 압축 인코딩은 Next.js가 자동 처리하므로 제거
    responseHeaders.delete('content-encoding');
    responseHeaders.delete('transfer-encoding');

    return new NextResponse(backendResponse.body, {
        status: backendResponse.status,
        headers: responseHeaders,
    });
}

export async function GET(request: NextRequest, { params }: { params: Promise<{ path: string[] }> }) {
    const { path } = await params;
    return proxyRequest(request, path);
}

export async function POST(request: NextRequest, { params }: { params: Promise<{ path: string[] }> }) {
    const { path } = await params;
    return proxyRequest(request, path);
}

export async function PUT(request: NextRequest, { params }: { params: Promise<{ path: string[] }> }) {
    const { path } = await params;
    return proxyRequest(request, path);
}

export async function PATCH(request: NextRequest, { params }: { params: Promise<{ path: string[] }> }) {
    const { path } = await params;
    return proxyRequest(request, path);
}

export async function DELETE(request: NextRequest, { params }: { params: Promise<{ path: string[] }> }) {
    const { path } = await params;
    return proxyRequest(request, path);
}
