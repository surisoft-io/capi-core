package capi.admin_only

import rego.v1

default allow := false

allow if {
    some role in input.realm_access.roles
    role in data.capi.admin_only.allowed_roles
}