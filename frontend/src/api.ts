import axios, { AxiosError } from 'axios';
import type {
  ApiError,
  Architecture,
  CFG,
  ComponentMap,
  Graph,
  SequenceDiagram,
  ServiceMap,
} from './types';

// Base URL for the backend REST API. Empty by default so requests stay
// relative and the Vite dev server proxies /api -> :8080. In production the
// SPA is served separately, so point it at the backend with VITE_API_BASE_URL.
axios.defaults.baseURL = import.meta.env.VITE_API_BASE_URL ?? '';

/**
 * Analyze a source directory. Calls GET /api/analyze?path=<encoded>.
 * On failure, throws an Error whose message is the backend `error` field
 * when present, otherwise a generic connection message.
 */
export async function analyze(path: string): Promise<Graph> {
  try {
    const response = await axios.get<Graph>('/api/analyze', {
      params: { path },
    });
    return response.data;
  } catch (err) {
    const axiosErr = err as AxiosError<ApiError>;
    const serverMessage = axiosErr.response?.data?.error;
    if (serverMessage) {
      throw new Error(serverMessage);
    }
    throw new Error('Connection failed — is the server running?');
  }
}

/** Fetch the Mermaid sequence diagram tracing a request from a function. */
export async function fetchSequence(
  path: string,
  functionId: string,
  maxDepth = 10
): Promise<SequenceDiagram> {
  try {
    const response = await axios.get<SequenceDiagram>('/api/sequence', {
      params: { path, functionId, maxDepth },
    });
    return response.data;
  } catch (err) {
    const axiosErr = err as AxiosError<ApiError>;
    const serverMessage = axiosErr.response?.data?.error;
    if (serverMessage) {
      throw new Error(serverMessage);
    }
    throw new Error('Connection failed — is the server running?');
  }
}

/**
 * Fetch the whole-workspace service-to-service topology. Calls
 * GET /api/servicemap?path=<workspace-dir>[&exclude=dir,dir]. The path should be
 * the directory that CONTAINS the individual services (not a single app).
 */
export async function fetchServiceMap(
  path: string,
  exclude?: string
): Promise<ServiceMap> {
  try {
    const response = await axios.get<ServiceMap>('/api/servicemap', {
      params: exclude ? { path, exclude } : { path },
    });
    return response.data;
  } catch (err) {
    const axiosErr = err as AxiosError<ApiError>;
    const serverMessage = axiosErr.response?.data?.error;
    if (serverMessage) {
      throw new Error(serverMessage);
    }
    throw new Error('Connection failed — is the server running?');
  }
}

/**
 * Fetch the intra-app component diagram (Spring beans + their dependencies) for
 * a single service. Calls GET /api/component?path=<app-dir>.
 */
export async function fetchComponentMap(path: string): Promise<ComponentMap> {
  try {
    const response = await axios.get<ComponentMap>('/api/component', {
      params: { path },
    });
    return response.data;
  } catch (err) {
    const axiosErr = err as AxiosError<ApiError>;
    const serverMessage = axiosErr.response?.data?.error;
    if (serverMessage) {
      throw new Error(serverMessage);
    }
    throw new Error('Connection failed — is the server running?');
  }
}

/**
 * Fetch the high-level layered architecture view for a single app.
 * Calls GET /api/architecture?path=<app-dir>.
 */
export async function fetchArchitecture(path: string): Promise<Architecture> {
  try {
    const response = await axios.get<Architecture>('/api/architecture', {
      params: { path },
    });
    return response.data;
  } catch (err) {
    const axiosErr = err as AxiosError<ApiError>;
    const serverMessage = axiosErr.response?.data?.error;
    if (serverMessage) {
      throw new Error(serverMessage);
    }
    throw new Error('Connection failed — is the server running?');
  }
}

/**
 * Fetch the endpoint-rooted, inter-procedural flow chart for a function.
 * Calls GET /api/flow?path=&functionId=&maxDepth=. Returns a CFG whose call
 * sites are expanded inline into the callee methods' flow.
 */
export async function fetchFlow(
  path: string,
  functionId: string,
  maxDepth = 4
): Promise<CFG> {
  try {
    const response = await axios.get<CFG>('/api/flow', {
      params: { path, functionId, maxDepth },
    });
    return response.data;
  } catch (err) {
    const axiosErr = err as AxiosError<ApiError>;
    const serverMessage = axiosErr.response?.data?.error;
    if (serverMessage) {
      throw new Error(serverMessage);
    }
    throw new Error('Connection failed — is the server running?');
  }
}
