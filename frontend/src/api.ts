import axios, { AxiosError } from 'axios';
import type {
  ApiError,
  Architecture,
  BusinessLogic,
  CFG,
  ComponentMap,
  FlowDescription,
  Graph,
  LlmStatus,
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
 * Fetch the step-by-step description of a flow chart. Calls
 * GET /api/flow-description?path=<dir>&functionId=<id>&maxDepth=<n>.
 * Pass the same maxDepth the chart was built with so the words match the graph.
 */
export async function fetchFlowDescription(
  path: string,
  functionId: string,
  maxDepth = 4
): Promise<FlowDescription> {
  try {
    const response = await axios.get<FlowDescription>('/api/flow-description', {
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
 * Explain a flow's business purpose. Calls
 * GET /api/business-logic?path=<dir>&functionId=<id>&maxDepth=<n>.
 * The response's `origin` says whether an LLM or offline heuristics produced it.
 */
export async function fetchBusinessLogic(
  path: string,
  functionId: string,
  maxDepth = 4
): Promise<BusinessLogic> {
  try {
    const response = await axios.get<BusinessLogic>('/api/business-logic', {
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
 * Report whether an LLM provider is configured. Calls GET /api/llm-status.
 * Returns a not-available status rather than throwing, so a failed probe just
 * means "assume offline" instead of breaking the view.
 */
export async function fetchLlmStatus(): Promise<LlmStatus> {
  try {
    const response = await axios.get<LlmStatus>('/api/llm-status');
    return response.data;
  } catch {
    return { available: false, provider: 'not configured' };
  }
}

/**
 * Fetch the whole-project architecture view. Calls GET /api/architecture?path=<dir>.
 * The path may be the project folder or any service inside it; a service path
 * comes back as the `focus` of the whole project's view.
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
