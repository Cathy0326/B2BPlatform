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

  # Security patches in a fixed low-traffic window (Sunday 03:00 UTC) instead of whenever Linode chooses.
  updates = {
    frequency   = "weekly"
    day_of_week = 7
    hour_of_day = 3
    duration    = 3
  }
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

# Cloud Firewall on every worker node: drop all inbound traffic except what Linode Kubernetes Engine needs
# (Linode's documented LKE rules). Public traffic only arrives through the NodeBalancer in front of
# ingress-nginx, never directly on a node.
resource "linode_firewall" "nodes" {
  label           = "quipmarket-${var.environment}-nodes"
  inbound_policy  = "DROP"
  outbound_policy = "ACCEPT"
  tags            = ["quipmarket", var.environment]

  inbound {
    label    = "kubelet-from-control-plane"
    action   = "ACCEPT"
    protocol = "TCP"
    ports    = "10250"
    ipv4     = ["192.168.128.0/17"]
  }
  inbound {
    label    = "wireguard-from-control-plane"
    action   = "ACCEPT"
    protocol = "UDP"
    ports    = "51820"
    ipv4     = ["192.168.128.0/17"]
  }
  inbound {
    label    = "calico-bgp"
    action   = "ACCEPT"
    protocol = "TCP"
    ports    = "179"
    ipv4     = ["192.168.128.0/17"]
  }
  inbound {
    label    = "calico-ipip"
    action   = "ACCEPT"
    protocol = "IPENCAP"
    ipv4     = ["192.168.128.0/17"]
  }
  inbound {
    label    = "nodeports-from-nodebalancers-tcp"
    action   = "ACCEPT"
    protocol = "TCP"
    ports    = "30000-32767"
    ipv4     = ["192.168.255.0/24"]
  }
  inbound {
    label    = "nodeports-from-nodebalancers-udp"
    action   = "ACCEPT"
    protocol = "UDP"
    ports    = "30000-32767"
    ipv4     = ["192.168.255.0/24"]
  }

  # Nodes added later by the autoscaler are covered on the next apply; run apply after scaling events.
  linodes = flatten([for pool in linode_lke_cluster.main.pool : [for node in pool.nodes : node.instance_id]])
}
