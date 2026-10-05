variable "environment" {
  description = "Which environment this state describes. Must match the selected workspace."
  type        = string
  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be dev, staging or prod."
  }
}

variable "linode_token" {
  description = "Linode API token (Cloud Manager > API Tokens). Pass via TF_VAR_linode_token, never commit."
  type        = string
  sensitive   = true
}

variable "cloudflare_api_token" {
  description = "Cloudflare API token with Zone.DNS edit permission. Pass via TF_VAR_cloudflare_api_token."
  type        = string
  sensitive   = true
}

variable "cloudflare_zone_id" {
  description = "Zone ID of your domain in Cloudflare."
  type        = string
}

variable "domain" {
  description = "Apex domain, e.g. quipmarket.example.com"
  type        = string
}

variable "region" {
  description = "Linode region, e.g. us-ord, eu-central, ap-south."
  type        = string
  default     = "us-ord"
}

variable "k8s_version" {
  description = "LKE Kubernetes version (see `linode-cli lke versions-list`)."
  type        = string
  default     = "1.33"
}

variable "node_type" {
  description = "Linode plan for worker nodes."
  type        = string
  default     = "g6-standard-2"
}

variable "node_count_min" {
  type    = number
  default = 2
}

variable "node_count_max" {
  type    = number
  default = 4
}

variable "db_type" {
  description = "Plan for the managed PostgreSQL cluster."
  type        = string
  default     = "g6-nanode-1"
}

variable "db_allow_list" {
  description = "CIDRs allowed to reach PostgreSQL: the LKE node IPs (each /32). Empty = nobody."
  type        = list(string)
  default     = []
  validation {
    condition     = alltrue([for cidr in var.db_allow_list : !contains(["0.0.0.0/0", "::/0"], cidr)])
    error_message = "Opening the database to the whole internet is not allowed; list the cluster node IPs."
  }
}

variable "ingress_ip" {
  description = "Public IP of the ingress-nginx LoadBalancer (known after installing ingress-nginx). Empty = no DNS records yet."
  type        = string
  default     = ""
}

variable "state_passphrase" {
  description = "Passphrase for OpenTofu state encryption (16+ characters). Pass via TF_VAR_state_passphrase, never commit."
  type        = string
  sensitive   = true
}
