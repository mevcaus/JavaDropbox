import { baseApi as api } from "./baseApi";
const injectedRtkApi = api.injectEndpoints({
  endpoints: (build) => ({
    listInvites: build.query<ListInvitesApiResponse, ListInvitesApiArg>({
      query: () => ({ url: `/api/admin/invites` }),
    }),
    createInvite: build.mutation<CreateInviteApiResponse, CreateInviteApiArg>({
      query: (queryArg) => ({
        url: `/api/admin/invites`,
        method: "POST",
        body: queryArg.body,
      }),
    }),
    revokeInvite: build.mutation<RevokeInviteApiResponse, RevokeInviteApiArg>({
      query: (queryArg) => ({
        url: `/api/admin/invites/${queryArg.id}`,
        method: "DELETE",
      }),
    }),
    listAccounts: build.query<ListAccountsApiResponse, ListAccountsApiArg>({
      query: () => ({ url: `/api/admin/users` }),
    }),
    setAccountEnabled: build.mutation<
      SetAccountEnabledApiResponse,
      SetAccountEnabledApiArg
    >({
      query: (queryArg) => ({
        url: `/api/admin/users/${queryArg.id}/enabled`,
        method: "PUT",
        body: queryArg.body,
      }),
    }),
    importFiles: build.mutation<ImportFilesApiResponse, ImportFilesApiArg>({
      query: (queryArg) => ({
        url: `/api/admin/users/${queryArg.id}/import`,
        method: "POST",
      }),
    }),
    createPasswordResetLink: build.mutation<
      CreatePasswordResetLinkApiResponse,
      CreatePasswordResetLinkApiArg
    >({
      query: (queryArg) => ({
        url: `/api/admin/users/${queryArg.id}/password-reset`,
        method: "POST",
      }),
    }),
    setAccountQuota: build.mutation<
      SetAccountQuotaApiResponse,
      SetAccountQuotaApiArg
    >({
      query: (queryArg) => ({
        url: `/api/admin/users/${queryArg.id}/quota`,
        method: "PUT",
        body: queryArg.body,
      }),
    }),
    setAccountRole: build.mutation<
      SetAccountRoleApiResponse,
      SetAccountRoleApiArg
    >({
      query: (queryArg) => ({
        url: `/api/admin/users/${queryArg.id}/role`,
        method: "PUT",
        body: queryArg.body,
      }),
    }),
    getDemoInfo: build.query<GetDemoInfoApiResponse, GetDemoInfoApiArg>({
      query: () => ({ url: `/api/demo` }),
    }),
    deleteItem: build.mutation<DeleteItemApiResponse, DeleteItemApiArg>({
      query: (queryArg) => ({
        url: `/api/files`,
        method: "DELETE",
        params: {
          path: queryArg.path,
        },
      }),
    }),
    getFileTree: build.query<GetFileTreeApiResponse, GetFileTreeApiArg>({
      query: () => ({ url: `/api/files` }),
    }),
    uploadFiles: build.mutation<UploadFilesApiResponse, UploadFilesApiArg>({
      query: (queryArg) => ({
        url: `/api/files`,
        method: "POST",
        body: queryArg.body,
      }),
    }),
    listFileVersions: build.query<
      ListFileVersionsApiResponse,
      ListFileVersionsApiArg
    >({
      query: (queryArg) => ({ url: `/api/files/${queryArg.fileId}/versions` }),
    }),
    restoreVersion: build.mutation<
      RestoreVersionApiResponse,
      RestoreVersionApiArg
    >({
      query: (queryArg) => ({
        url: `/api/files/${queryArg.fileId}/versions/${queryArg.version}/restore`,
        method: "POST",
        params: {
          mode: queryArg.mode,
        },
      }),
    }),
    createFolder: build.mutation<CreateFolderApiResponse, CreateFolderApiArg>({
      query: (queryArg) => ({
        url: `/api/folders`,
        method: "POST",
        body: queryArg.body,
      }),
    }),
    getHistory: build.query<GetHistoryApiResponse, GetHistoryApiArg>({
      query: (queryArg) => ({
        url: `/api/history`,
        params: {
          page: queryArg.page,
          size: queryArg.size,
        },
      }),
    }),
    describeInvite: build.query<
      DescribeInviteApiResponse,
      DescribeInviteApiArg
    >({
      query: (queryArg) => ({ url: `/api/invite/${queryArg.token}` }),
    }),
    acceptInvite: build.mutation<AcceptInviteApiResponse, AcceptInviteApiArg>({
      query: (queryArg) => ({
        url: `/api/invite/${queryArg.token}`,
        method: "POST",
        body: queryArg.body,
      }),
    }),
    getCurrentUser: build.query<
      GetCurrentUserApiResponse,
      GetCurrentUserApiArg
    >({
      query: () => ({ url: `/api/me` }),
    }),
    describePasswordReset: build.query<
      DescribePasswordResetApiResponse,
      DescribePasswordResetApiArg
    >({
      query: (queryArg) => ({ url: `/api/reset-password/${queryArg.token}` }),
    }),
    resetPassword: build.mutation<
      ResetPasswordApiResponse,
      ResetPasswordApiArg
    >({
      query: (queryArg) => ({
        url: `/api/reset-password/${queryArg.token}`,
        method: "POST",
        body: queryArg.body,
      }),
    }),
    searchFiles: build.query<SearchFilesApiResponse, SearchFilesApiArg>({
      query: (queryArg) => ({
        url: `/api/search`,
        params: {
          q: queryArg.q,
          path: queryArg.path,
          limit: queryArg.limit,
        },
      }),
    }),
    listShareLinks: build.query<
      ListShareLinksApiResponse,
      ListShareLinksApiArg
    >({
      query: (queryArg) => ({
        url: `/api/share`,
        params: {
          path: queryArg.path,
        },
      }),
    }),
    createShareLink: build.mutation<
      CreateShareLinkApiResponse,
      CreateShareLinkApiArg
    >({
      query: (queryArg) => ({
        url: `/api/share`,
        method: "POST",
        body: queryArg.body,
      }),
    }),
    revokeShareLink: build.mutation<
      RevokeShareLinkApiResponse,
      RevokeShareLinkApiArg
    >({
      query: (queryArg) => ({
        url: `/api/share/${queryArg.id}`,
        method: "DELETE",
      }),
    }),
    getStorageUsage: build.query<
      GetStorageUsageApiResponse,
      GetStorageUsageApiArg
    >({
      query: () => ({ url: `/api/storage` }),
    }),
    completeSetup: build.mutation<
      CompleteSetupApiResponse,
      CompleteSetupApiArg
    >({
      query: (queryArg) => ({
        url: `/setup`,
        method: "POST",
        body: queryArg.body,
      }),
    }),
    describeSharedItem: build.query<
      DescribeSharedItemApiResponse,
      DescribeSharedItemApiArg
    >({
      query: (queryArg) => ({ url: `/share/${queryArg.token}/info` }),
    }),
  }),
  overrideExisting: false,
});
export { injectedRtkApi as generatedApi };
export type ListInvitesApiResponse = /** status 200 OK */ InviteDto[];
export type ListInvitesApiArg = void;
export type CreateInviteApiResponse = /** status 200 OK */ NewLink;
export type CreateInviteApiArg = {
  body: {
    quota?: string;
    role?: string;
    username: string;
  };
};
export type RevokeInviteApiResponse = /** status 200 OK */ Message;
export type RevokeInviteApiArg = {
  id: number;
};
export type ListAccountsApiResponse = /** status 200 OK */ AccountDto[];
export type ListAccountsApiArg = void;
export type SetAccountEnabledApiResponse = /** status 200 OK */ Message;
export type SetAccountEnabledApiArg = {
  id: number;
  body: {
    enabled: boolean;
  };
};
export type ImportFilesApiResponse = /** status 200 OK */ ImportResult;
export type ImportFilesApiArg = {
  id: number;
};
export type CreatePasswordResetLinkApiResponse = /** status 200 OK */ NewLink;
export type CreatePasswordResetLinkApiArg = {
  id: number;
};
export type SetAccountQuotaApiResponse = /** status 200 OK */ Message;
export type SetAccountQuotaApiArg = {
  id: number;
  body: {
    quota?: string;
  };
};
export type SetAccountRoleApiResponse = /** status 200 OK */ Message;
export type SetAccountRoleApiArg = {
  id: number;
  body: {
    role: string;
  };
};
export type GetDemoInfoApiResponse = /** status 200 OK */ DemoInfo;
export type GetDemoInfoApiArg = void;
export type DeleteItemApiResponse = /** status 200 OK */ Message;
export type DeleteItemApiArg = {
  path: string;
};
export type GetFileTreeApiResponse = /** status 200 OK */ FileTreeNode[];
export type GetFileTreeApiArg = void;
export type UploadFilesApiResponse = /** status 200 OK */ Message;
export type UploadFilesApiArg = {
  body: {
    files: Blob[];
    path?: string;
  };
};
export type ListFileVersionsApiResponse = /** status 200 OK */ FileVersionDto[];
export type ListFileVersionsApiArg = {
  fileId: number;
};
export type RestoreVersionApiResponse = /** status 200 OK */ Message;
export type RestoreVersionApiArg = {
  fileId: number;
  version: number;
  mode?: "OVERWRITE" | "COPY";
};
export type CreateFolderApiResponse = /** status 200 OK */ Message;
export type CreateFolderApiArg = {
  body: {
    name: string;
    path?: string;
  };
};
export type GetHistoryApiResponse = /** status 200 OK */ HistoryPage;
export type GetHistoryApiArg = {
  page?: number;
  size?: number;
};
export type DescribeInviteApiResponse = /** status 200 OK */ AccountLinkInfo;
export type DescribeInviteApiArg = {
  token: string;
};
export type AcceptInviteApiResponse = /** status 200 OK */ Message;
export type AcceptInviteApiArg = {
  token: string;
  body: {
    password?: string;
  };
};
export type GetCurrentUserApiResponse = /** status 200 OK */ CurrentUser;
export type GetCurrentUserApiArg = void;
export type DescribePasswordResetApiResponse =
  /** status 200 OK */ AccountLinkInfo;
export type DescribePasswordResetApiArg = {
  token: string;
};
export type ResetPasswordApiResponse = /** status 200 OK */ Message;
export type ResetPasswordApiArg = {
  token: string;
  body: {
    password?: string;
  };
};
export type SearchFilesApiResponse = /** status 200 OK */ SearchResults;
export type SearchFilesApiArg = {
  q: string;
  path?: string;
  limit?: number;
};
export type ListShareLinksApiResponse = /** status 200 OK */ ShareLinkDto[];
export type ListShareLinksApiArg = {
  path: string;
};
export type CreateShareLinkApiResponse = /** status 200 OK */ NewLink;
export type CreateShareLinkApiArg = {
  body: {
    expirationMinutes?: number;
    path: string;
  };
};
export type RevokeShareLinkApiResponse = /** status 200 OK */ Message;
export type RevokeShareLinkApiArg = {
  id: number;
};
export type GetStorageUsageApiResponse = /** status 200 OK */ Usage;
export type GetStorageUsageApiArg = void;
export type CompleteSetupApiResponse = /** status 200 OK */ Message;
export type CompleteSetupApiArg = {
  body: {
    code?: string;
    password?: string;
    username?: string;
  };
};
export type DescribeSharedItemApiResponse = /** status 200 OK */ SharedItemDto;
export type DescribeSharedItemApiArg = {
  token: string;
};
export type InviteDto = {
  createdAt: string;
  createdBy: string | null;
  expiresAt: string;
  id: number;
  quotaBytes: number | null;
  role: string;
  username: string;
};
export type NewLink = {
  expiresAt: string;
  url: string;
};
export type Message = {
  message: string;
};
export type AccountDto = {
  enabled: boolean;
  id: number;
  quotaBytes: number | null;
  role: string;
  usedBytes: number;
  username: string;
};
export type ImportResult = {
  files: number;
  folders: number;
  missing: number;
  skipped: number;
};
export type DemoInfo = {
  maxShareMinutes: number;
  maxUploadBytes: number;
  nextReset: string;
  password: string;
  storageLimitBytes: number | null;
  username: string;
};
export type FileTreeNode = {
  children: FileTreeNode[];
  createdDate: string | null;
  id: number | null;
  isDirectory: boolean;
  lastModified: string | null;
  name: string;
  ownerName: string;
  previewType:
    ("image" | "pdf" | "text" | null) | ("image" | "pdf" | "text" | null);
  relativePath: string;
  size: number;
};
export type FileVersionDto = {
  createdAt: string | null;
  createdBy: string;
  id: number;
  size: number | null;
  version: number;
};
export type FileHistoryDto = {
  changeType:
    | ("UPLOAD" | "DELETE" | "CREATE_FOLDER" | "RESTORE" | null)
    | ("UPLOAD" | "DELETE" | "CREATE_FOLDER" | "RESTORE" | null);
  details: string | null;
  errorMessage: string | null;
  filePath: string | null;
  filename: string | null;
  id: number;
  success: boolean;
  timestamp: string | null;
  username: string;
};
export type HistoryPage = {
  items: FileHistoryDto[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
};
export type AccountLinkInfo = {
  expiresAt: string;
  username: string;
};
export type CurrentUser = {
  role: string;
  username: string;
};
export type Highlight = {
  end: number;
  start: number;
};
export type Snippet = {
  highlights: Highlight[];
  text: string;
};
export type SearchResult = {
  isDirectory: boolean;
  lastModified: string | null;
  name: string;
  previewType:
    ("image" | "pdf" | "text" | null) | ("image" | "pdf" | "text" | null);
  relativePath: string;
  size: number | null;
  snippet: Snippet | null;
};
export type SearchResults = {
  indexing: boolean;
  results: SearchResult[];
  total: number;
};
export type ShareLinkDto = {
  createdAt: string;
  createdBy: string;
  expiresAt: string;
  id: number;
};
export type Usage = {
  quotaBytes: number | null;
  usedBytes: number;
};
export type SharedEntry = {
  children: SharedEntry[] | null;
  isDirectory: boolean;
  lastModified: string | null;
  name: string;
  size: number;
};
export type SharedItemDto = {
  contents: SharedEntry[] | null;
  expiresAt: string;
  isDirectory: boolean;
  lastModified: string | null;
  name: string;
  previewType:
    ("image" | "pdf" | "text" | null) | ("image" | "pdf" | "text" | null);
  size: number;
};
export const {
  useListInvitesQuery,
  useCreateInviteMutation,
  useRevokeInviteMutation,
  useListAccountsQuery,
  useSetAccountEnabledMutation,
  useImportFilesMutation,
  useCreatePasswordResetLinkMutation,
  useSetAccountQuotaMutation,
  useSetAccountRoleMutation,
  useGetDemoInfoQuery,
  useDeleteItemMutation,
  useGetFileTreeQuery,
  useUploadFilesMutation,
  useListFileVersionsQuery,
  useRestoreVersionMutation,
  useCreateFolderMutation,
  useGetHistoryQuery,
  useDescribeInviteQuery,
  useAcceptInviteMutation,
  useGetCurrentUserQuery,
  useDescribePasswordResetQuery,
  useResetPasswordMutation,
  useSearchFilesQuery,
  useListShareLinksQuery,
  useCreateShareLinkMutation,
  useRevokeShareLinkMutation,
  useGetStorageUsageQuery,
  useCompleteSetupMutation,
  useDescribeSharedItemQuery,
} = injectedRtkApi;
