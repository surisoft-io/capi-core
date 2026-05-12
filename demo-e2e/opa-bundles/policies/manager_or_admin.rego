package capi.manager_or_admin

import rego.v1

default allow := false

allow if {
    some role in input.realm_access.roles
    role in data.capi.manager_or_admin.allowed_roles
}