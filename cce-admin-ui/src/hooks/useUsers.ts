import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import type {
  ChangeUserStatusRequest,
  CreateUserRequest,
  ReplaceUserRolesRequest,
  UpdateUserRequest,
  UserListQuery,
} from '../api/contract';
import {
  changeUserStatus,
  createUser,
  getUser,
  listUsers,
  replaceUserRoles,
  resetUserPassword,
  updateUser,
} from '../api/users';
import { queryKeys } from './queryKeys';

export function useUserList(query: UserListQuery) {
  return useQuery({
    queryKey: queryKeys.userList(query),
    queryFn: () => listUsers(query),
    placeholderData: keepPreviousData,
  });
}

export function useUser(id: string) {
  return useQuery({ queryKey: queryKeys.userDetail(id), queryFn: () => getUser(id) });
}

export function useCreateUser() {
  return useMutation({ mutationFn: (body: CreateUserRequest) => createUser(body) });
}

export function useUpdateUser() {
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: UpdateUserRequest }) => updateUser(id, body),
  });
}

export function useChangeUserStatus() {
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: ChangeUserStatusRequest }) =>
      changeUserStatus(id, body),
  });
}

export function useReplaceUserRoles() {
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: ReplaceUserRolesRequest }) =>
      replaceUserRoles(id, body),
  });
}

export function useResetUserPassword() {
  return useMutation({ mutationFn: (id: string) => resetUserPassword(id) });
}
