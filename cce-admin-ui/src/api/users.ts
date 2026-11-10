import { ApiError, CLIENT_UNEXPECTED_RESPONSE } from './ApiError';
import { apiRequest } from './client';
import type {
  ChangeUserStatusRequest,
  CreateUserRequest,
  ReplaceUserRolesRequest,
  ResetPasswordResult,
  UpdateUserRequest,
  User,
  UserListQuery,
} from './contract';
import type { Page, WithWarnings } from './results';

const userPath = (id: string) => `/users/${encodeURIComponent(id)}`;

/** One page of users; undefined, null and empty query values are left out by the client. */
export async function listUsers(query: UserListQuery): Promise<Page<User>> {
  const { status, roleId, facilityScope, q, page, pageSize } = query;
  const envelope = await apiRequest<User[]>('GET', '/users', {
    query: { status, roleId, facilityScope, q, page, pageSize },
  });
  if (!envelope.pagination) {
    // As in listRoles: a paginated list without its pagination block is not the contract.
    throw new ApiError({
      status: 200,
      code: CLIENT_UNEXPECTED_RESPONSE,
      message: 'The admin service answered a paginated list without its pagination block.',
      hasEnvelope: false,
    });
  }
  return { items: envelope.data, pagination: envelope.pagination };
}

export async function getUser(id: string): Promise<User> {
  return (await apiRequest<User>('GET', userPath(id))).data;
}

/** Creates the user, or links a matching Keycloak account; maybe `ACTION_EMAIL_NOT_SENT`. */
export async function createUser(body: CreateUserRequest): Promise<WithWarnings<User>> {
  const envelope = await apiRequest<User>('POST', '/users', { body });
  return { data: envelope.data, warnings: envelope.warnings ?? [] };
}

export async function updateUser(id: string, body: UpdateUserRequest): Promise<User> {
  return (await apiRequest<User>('PUT', userPath(id), { body })).data;
}

export async function changeUserStatus(id: string, body: ChangeUserStatusRequest): Promise<User> {
  return (await apiRequest<User>('PATCH', `${userPath(id)}/status`, { body })).data;
}

/** Sends the complete new role set (replace semantics, admin-service §5.4). */
export async function replaceUserRoles(id: string, body: ReplaceUserRolesRequest): Promise<User> {
  return (await apiRequest<User>('PUT', `${userPath(id)}/roles`, { body })).data;
}

/** Sends the set-password email (`POST /users/:id/reset-password`, no body). */
export async function resetUserPassword(id: string): Promise<ResetPasswordResult> {
  return (await apiRequest<ResetPasswordResult>('POST', `${userPath(id)}/reset-password`)).data;
}
