package capi.allow_all

import rego.v1

default allow := false

allow if {
    some role in input.realm_access.roles
    role in data.capi.allow_all.allowed_roles
}