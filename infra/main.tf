# Kubernetes cluster (Linode Kubernetes Engine) with an autoscaling node pool.
resource "linode_lke_cluster" "main" {
  label       = "quipmarket-${var.environment}"
  k8s_version = var.k8s_version
  region      = var.region
  tags        = ["quipmarket", var.environment]

  pool {
    type  = var.node_type
    count = var.node_count_min

    autoscaler {
      min = var.node_count_min
      max = var.node_count_max
    }
  }

  lifecycle {
    # Each environment has its own state (one workspace per environment). Applying prod.tfvars inside the
    # staging workspace would rename and resize the staging cluster, so refuse before anything changes.
    precondition {
      condition     = terraform.workspace == var.environment
      error_message = "Workspace and environment differ. Run: tofu workspace select ${var.environment}"
    }
  }
}

# Managed PostgreSQL 16: backups, patching and TLS handled by Linode.
resource "linode_database_postgresql_v2" "main" {
  label        = "quipmarket-${var.environment}-db"
  engine_id    = "postgresql/16"
  region       = var.region
  type         = var.db_type
  cluster_size = 1
  allow_list   = var.db_allow_list
}

# DNS in Cloudflare, pointing at the ingress load balancer.
resource "cloudflare_dns_record" "app" {
  count   = var.ingress_ip == "" ? 0 : 1
  zone_id = var.cloudflare_zone_id
  name    = var.domain
  type    = "A"
  content = var.ingress_ip
  ttl     = 1
  proxied = true
}

resource "cloudflare_dns_record" "api" {
  count   = var.ingress_ip == "" ? 0 : 1
  zone_id = var.cloudflare_zone_id
  name    = "api.${var.domain}"
  type    = "A"
  content = var.ingress_ip
  ttl     = 1
  proxied = true
}
