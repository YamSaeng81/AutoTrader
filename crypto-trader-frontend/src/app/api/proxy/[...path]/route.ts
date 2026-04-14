import { NextRequest, NextResponse } from 'next/server';
import { Agent } from 'undici';

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

// 백테스트처럼 오래 걸리는 요청용 — undici 기본 headersTimeout(5분) 우회
const longTimeoutAgent = new Agent({
    headersTimeout: 30 * 60 * 1000,  // 30분
    bodyTimeout: 30 * 60 * 1000,
});

const LONG_TIMEOUT_PATHS = ['/api/v1/backtest'];

async function proxyRequest(request: NextRequest, pathSegments: string[]): Promise<NextResponse> {
    const backendPath = '/' + pathSegments.join('/');
    const search = request.nextUrl.search;
    const targetUrl = `${BACKEND_URL}${backendPath}${search}`;

    const headers = new Headers(request.headers);
    headers.delete('host');
    // 브라우저 Origin 헤더 제거: 프록시는 서버→서버 호출이므로 CORS 불필요
    // 이 헤더를 그대로 전달하면 백엔드 CORS 필터가 브라우저 Origin을 검사해 403 반환
    headers.delete('origin');

    if (API_TOKEN) {
        headers.set('Authorization', `Bearer ${API_TOKEN}`);
    }

    let body: BodyInit | null = null;
    const method = request.method;
    if (method !== 'GET' && method !== 'HEAD') {
        body = await request.blob();
    }

    const isLongTimeout = LONG_TIMEOUT_PATHS.some(p => backendPath.startsWith(p));

    let backendResponse: Response;
    try {
        backendResponse = await fetch(targetUrl, {
            method,
            headers,
            body,
            // @ts-expect-error — Node.js fetch duplex/dispatcher 옵션
            duplex: 'half',
            dispatcher: isLongTimeout ? longTimeoutAgent : undefined,
        });
    } catch (err: any) {
        const isConnRefused = err?.cause?.code === 'ECONNREFUSED' || err?.message?.includes('ECONNREFUSED');
        return NextResponse.json(
            { success: false, errorCode: 'BACKEND_UNAVAILABLE', message: '백엔드 서버에 연결할 수 없습니다. 서버가 실행 중인지 확인하세요.' },
            { status: 503 }
        );
    }

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
