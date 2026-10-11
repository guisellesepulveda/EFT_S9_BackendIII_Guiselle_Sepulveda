# Apagar y eliminar el despliegue (limpieza de costos) — Learner Lab

Se ejecuta **desde el portátil**, con las variables de `infra/aws/.deploy-state` (las escribe el runbook de `despliegue.md`, Parte A) y la sesión del lab iniciada
(*Start Lab* + credenciales en `~/.aws/credentials`, perfil `learnerlab`).

## 1. Pausar (conserva datos y la IP elástica; deja de cobrar cómputo)

```bash
source infra/aws/.deploy-state
export AWS_PROFILE=learnerlab AWS_REGION=us-east-1 AWS_PAGER=""
aws ec2 stop-instances --instance-ids $APPS_ID
```
El volumen EBS sigue cobrándose mientras exista. La **IP elástica se conserva** al detener la instancia; por eso el certificado y las reglas siguen valiendo al volver a encenderla
(`aws ec2 start-instances --instance-ids $APPS_ID`). Learner Lab también detiene la instancia solo al expirar la sesión.

## 2. Eliminar todo (fin de la evaluación, cuando ya tiene todas las evidencias)

```bash
source infra/aws/.deploy-state
aws ec2 terminate-instances --instance-ids $APPS_ID                 # el volumen raíz se elimina con la instancia (DeleteOnTermination)
aws ec2 wait instance-terminated --instance-ids $APPS_ID
aws ec2 release-address --allocation-id $ALLOC_ID                   # una IP elástica SIN asociar sigue cobrando: hay que liberarla
aws ec2 delete-security-group --group-id $SG_APPS                   # solo se puede borrar cuando la instancia ya terminó
```

## 3. Comprobar que no queda nada que cobre

```bash
aws ec2 describe-instances --filters Name=tag:Project,Values=banco-xyz-eft Name=instance-state-name,Values=running,stopped --query 'Reservations[].Instances[].InstanceId'
aws ec2 describe-addresses --query 'Addresses[].PublicIp'                                                # IP elásticas: debe quedar vacío
aws ec2 describe-volumes --filters Name=status,Values=available --query 'Volumes[].VolumeId'            # volúmenes huérfanos → aws ec2 delete-volume
```

Por consola: EC2 → Instances (Terminate), EC2 → **Elastic IPs** (Release), EC2 → Volumes (sin volúmenes `available`) y EC2 → Security Groups (borrar `banco-xyz-apps-sg`).
Termine el lab con **End Lab** y revise el presupuesto restante en el panel.

## Datos
Los datos de la demo viven solo en los volúmenes Docker de la instancia (`postgres-data`, `kafka-data`). Antes de terminar, copie al portátil las evidencias:
`mkdir -p evidencias/aws && scp -i $SSH_KEY -r ec2-user@$APPS_IP:banco-xyz/evidencias/aws/logs evidencias/aws/`.
