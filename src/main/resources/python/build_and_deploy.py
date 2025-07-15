import os
import subprocess

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..', '..'))
MS_PATH = os.path.abspath(os.path.join(ROOT_DIR, '..', 'central-consig-bancos-dados-cliente'))

def run_command(description, command, cwd=None):
    print(f"\n{description}")
    result = subprocess.run(command, shell=True, cwd=cwd)
    if result.returncode != 0:
        print(f"Erro ao executar: {command}")
        exit(1)

def git_commit_push(project_path, commit_msg):
    run_command("Adicionando arquivos ao Git", "git add .", cwd=project_path)
    run_command(f"Commitando alterações: {commit_msg}", f'git commit -m "{commit_msg}"', cwd=project_path)
    run_command("Enviando para o repositório remoto", "git push origin master", cwd=project_path)

def build_and_push_docker(micro_path):
    image_name = f"bossaws2024/{os.path.basename(micro_path)}:latest"

    print(f"\nBuildando imagem Docker para {image_name}...")
    run_command(f"Build Docker image: {image_name}", f"docker build -t {image_name} .", cwd=micro_path)

    print(f"Fazendo push da imagem {image_name} para o Docker Hub...")
    run_command(f"Push Docker image: {image_name}", f"docker push {image_name}", cwd=micro_path)

# ---- Execução
print("ROOT_DIR = ", ROOT_DIR)
run_command("Limpando build com Maven", "mvn clean", cwd=MS_PATH)

commit_msg = input("\nDigite a mensagem do commit: ")
git_commit_push(MS_PATH, commit_msg)

run_command("Instalando build localmente", "mvn install", cwd=MS_PATH)

build_and_push_docker(MS_PATH)

print("\nMicrosserviço rebuildado e atualizado com sucesso!")
