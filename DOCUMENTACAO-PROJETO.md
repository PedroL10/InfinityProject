# Documentação do Projeto — sb-ecom (API de E-commerce)

Documento de arquitetura e referência técnica do projeto, mantido como material de apoio para explicar o projeto em entrevistas. Diferente do `notas-de-estudo.md` (que registra perguntas e respostas pontuais sobre conceitos), este arquivo descreve o projeto como um todo: visão geral, arquitetura, decisões de design e pontos de atenção.

> Última atualização: 2026-09-21 (módulo de Categorias — paginação, ordenação e DTOs simétricos)

---

## 1. Visão geral (pitch de entrevista)

É uma **API REST de e-commerce** construída em **Spring Boot**, atualmente com CRUD de Categorias de produtos. O projeto segue arquitetura em camadas (Controller → Service → Repository), usa **JPA/Hibernate** para persistência, **Bean Validation** para validação de entrada, **DTOs** para não expor a entidade de banco diretamente na API, e um **tratamento de exceções centralizado** via `@RestControllerAdvice`.

Frase curta para entrevista: *"É uma API REST em Spring Boot para um sistema de e-commerce, onde apliquei arquitetura em camadas, injeção de dependência via interfaces, DTOs com ModelMapper (simétrico — entrada e saída) para desacoplar a API do modelo de persistência, validação de entrada com Bean Validation, paginação e ordenação via Spring Data (`Pageable`/`Sort`), e tratamento de exceções centralizado com `@RestControllerAdvice`, incluindo um envelope de resposta padronizado (`APIResponse`) para erros."*

## 2. Stack tecnológica

| Tecnologia | Papel no projeto |
|---|---|
| **Java 21** | Linguagem |
| **Spring Boot 4.1.1** | Framework principal (autoconfiguração, servidor embutido) |
| **Spring Web (MVC)** | Controllers REST (`@RestController`) |
| **Spring Data JPA** | Camada de persistência (`JpaRepository`) |
| **Hibernate** | Implementação de JPA (ORM) |
| **H2 Database** | Banco de dados em memória (usado em desenvolvimento) |
| **Bean Validation** (`spring-boot-starter-validation`) | Validação declarativa (`@NotBlank`, `@Size`) |
| **ModelMapper** | Conversão automática Entity ↔ DTO |
| **Lombok** | Geração automática de getters/setters/construtores (`@Data`, `@NoArgsConstructor`, `@AllArgsConstructor`) |
| **DevTools** | Restart automático da aplicação durante desenvolvimento |

## 3. Arquitetura em camadas

```
Cliente HTTP
     │
     ▼
┌─────────────────────┐
│    Controller        │  @RestController — recebe HTTP, valida entrada (@Valid),
│  CategoryController   │  delega pro service, devolve ResponseEntity
└─────────┬────────────┘
          │  depende da INTERFACE
          ▼
┌─────────────────────┐
│      Service          │  interface CategoryService (contrato)
│  CategoryService /     │  + CategoryServiceImpl (regra de negócio,
│  CategoryServiceImpl   │    validações de domínio, conversão Entity↔DTO)
└─────────┬────────────┘
          │
          ▼
┌─────────────────────┐
│     Repository         │  interface CategoryRepository extends JpaRepository
│  CategoryRepository     │  (Spring Data gera a implementação em runtime)
└─────────┬────────────┘
          │
          ▼
┌─────────────────────┐
│        Model            │  @Entity Category — mapeada para a tabela "categories"
│       Category           │
└─────────┬────────────┘
          │
          ▼
     Banco H2 (em memória)
```

Transversal a todas as camadas:
- **`payload/`** — DTOs (`CategoryDTO`, `CategoryResponse`, `APIResponse`) que trafegam entre Controller e cliente, mantendo a entidade `Category` isolada dentro do backend. `Category` agora nunca cruza a fronteira do Controller (nem entrada, nem saída) — só `CategoryDTO`.
- **`exceptions/`** — exceções de domínio (`ResourceNotFoundException`, `APIException`) + handler global (`MyGlobalExceptionHandler`) que intercepta erros de qualquer camada e os converte em respostas HTTP (usando `APIResponse` como corpo padronizado).
- **`config/`** — configuração de beans que não vêm prontos do Spring Boot (`AppConfig` define o bean `ModelMapper`) e constantes de configuração compartilhadas (`AppConstants` — valores padrão de paginação/ordenação).

## 4. Estrutura de pacotes

```
com.ecommerce.project
├── ProjectApplication.java     # classe main (@SpringBootApplication)
├── config/
│   ├── AppConfig.java            # define o bean ModelMapper
│   └── AppConstants.java         # valores padrão de paginação/ordenação
├── controller/
│   └── CategoryController.java  # endpoints REST
├── service/
│   ├── CategoryService.java      # interface (contrato)
│   └── CategoryServiceImpl.java  # implementação (regra de negócio)
├── repositories/
│   └── CategoryRepository.java   # interface JpaRepository (Spring Data)
├── model/
│   └── Category.java              # entidade JPA (mapeia a tabela)
├── payload/
│   ├── CategoryDTO.java           # DTO de categoria (id + nome) — usado na entrada e na saída
│   ├── CategoryResponse.java      # DTO de resposta da listagem (conteúdo + metadados de paginação)
│   └── APIResponse.java           # DTO envelope {message, status} para respostas de erro
└── exceptions/
    ├── ResourceNotFoundException.java
    ├── APIException.java
    └── MyGlobalExceptionHandler.java
```

## 5. Fluxo completo de uma requisição — exemplo: `GET /api/public/categories?pageNumber=0&pageSize=5&sortBy=categoryName&sortOrder=desc`

1. Cliente faz o `GET` com os 4 parâmetros de query string (ou omite algum — os defaults de `AppConstants` entram em ação via `@RequestParam(defaultValue = ...)`).
2. `CategoryController.getAllCategories(...)` chama `categoryService.getAllCategories(pageNumber, pageSize, sortBy, sortOrder)`.
3. `CategoryServiceImpl.getAllCategories(...)`:
   - monta um `Sort` (`Sort.by(sortBy).ascending()/.descending()`) e um `Pageable` (`PageRequest.of(pageNumber, pageSize, sort)`);
   - busca via `categoryRepository.findAll(pageDetails)`, que devolve um `Page<Category>` (itens da página + metadados: total de elementos, total de páginas, se é a última);
   - se a página não tiver itens, lança `APIException("No category created till now.")`;
   - converte cada `Category` (entidade) em `CategoryDTO` usando `modelMapper.map(category, CategoryDTO.class)` — evita expor a entidade JPA diretamente na API;
   - empacota a lista de DTOs + os metadados de paginação dentro de um `CategoryResponse`.
4. O Controller devolve `ResponseEntity<CategoryResponse>` com status 200.
5. Se, em vez disso, a página estivesse vazia, a `APIException` lançada no passo 3 subiria sem tratamento local, seria interceptada por `MyGlobalExceptionHandler.myAPIException`, que devolveria um `APIResponse` (`{message, status: false}`) com 400 Bad Request.

Fluxo análogo para `POST`/`PUT`/`DELETE`, todos recebendo/devolvendo `CategoryDTO` (nunca a entidade `Category` diretamente) — o service converte DTO→Entity antes de persistir e Entity→DTO antes de responder, via `ModelMapper`, nos dois sentidos.

## 6. Padrões de projeto e decisões aplicadas

| Padrão / decisão | Onde aparece | Por quê |
|---|---|---|
| **Interface + Implementação** (`Service`/`ServiceImpl`) | `CategoryService` / `CategoryServiceImpl` | Desacopla o Controller da implementação concreta; facilita testes (mock da interface); convenção Spring. |
| **DTO (Data Transfer Object)** | `CategoryDTO`, `CategoryResponse` | Evita expor a entidade JPA (`Category`) diretamente na API — desacopla o contrato público da API do modelo de persistência interno. Mudanças no banco não quebram automaticamente o contrato da API, e vice-versa. |
| **Object Mapper automático** | `ModelMapper` em `CategoryServiceImpl` | Evita escrever manualmente `dto.setCategoryId(entity.getCategoryId())` para cada campo — mapeia por convenção (nomes de campo iguais). |
| **Repository Pattern** | `CategoryRepository extends JpaRepository` | Spring Data gera a implementação do CRUD (e de métodos derivados como `findByCategoryName`) em tempo de execução, sem escrever SQL manualmente. |
| **Exception Handling centralizado** | `MyGlobalExceptionHandler` (`@RestControllerAdvice`) | Um único lugar decide qual status HTTP corresponde a cada tipo de erro, evitando `try/catch` repetido em cada endpoint. |
| **Validação declarativa (Bean Validation)** | `@NotBlank`, `@Size` em `Category` + `@Valid` no Controller | Validação de entrada roda automaticamente antes do método do controller ser chamado; erros viram `MethodArgumentNotValidException`, tratada globalmente. |
| **Redução de boilerplate com Lombok** | `@Data`, `@NoArgsConstructor`, `@AllArgsConstructor` em `Category`, `CategoryDTO`, `CategoryResponse`, `APIResponse` | Gera getters, setters, `equals`/`hashCode`, `toString` e construtores automaticamente em tempo de compilação. |
| **Paginação e ordenação via Spring Data** | `Pageable`/`PageRequest`/`Sort`/`Page<Category>` em `CategoryServiceImpl.getAllCategories` | Evita carregar a tabela inteira em memória; Spring Data gera a query com `LIMIT`/`OFFSET`/`ORDER BY` a partir de objetos, sem SQL manual. |
| **Query params com valor padrão centralizado** | `@RequestParam(defaultValue = AppConstants...)` no Controller + `AppConstants` | Evita strings mágicas espalhadas; um único lugar define os padrões de paginação/ordenação da API. |
| **Envelope de resposta padronizado para erros** | `APIResponse` usado em `MyGlobalExceptionHandler` | Erros voltam como objeto estruturado (`{message, status}`), consistente com o resto da API, em vez de texto cru. |

## 7. Endpoints da API

| Método | Endpoint | Query params | Request body | Resposta de sucesso | Camada que valida/lança erro |
|---|---|---|---|---|---|
| GET | `/api/public/categories` | `pageNumber`, `pageSize`, `sortBy`, `sortOrder` (todos opcionais, com default em `AppConstants`) | — | 200 + `CategoryResponse` (lista de `CategoryDTO` + metadados de paginação) | `APIException` (400) se a página não tiver categorias |
| POST | `/api/public/categories` | — | `CategoryDTO` (JSON: `categoryName`) | 201 + `CategoryDTO` criado | `@Valid` (400) se nome inválido; `APIException` (400) se nome duplicado |
| PUT | `/api/public/categories/{categoryId}` | — | `CategoryDTO` | 200 + `CategoryDTO` atualizado | `ResourceNotFoundException` (404) se ID não existir |
| DELETE | `/api/admin/categories/{categoryId}` | — | — | 200 + `CategoryDTO` removido | `ResourceNotFoundException` (404) se ID não existir |

Erros (`APIException`/`ResourceNotFoundException`) sempre voltam como `APIResponse` (`{message, status: false}`); falhas de `@Valid` voltam como `Map<String,String>` (`{campo: mensagem}`).

> Nota de nomenclatura: os endpoints já diferenciam prefixo `/public/` de `/admin/` — provável preparação para adicionar segurança (Spring Security) mais à frente, restringindo `/admin/**` a usuários autenticados/admin.

## 8. Conceitos para citar numa entrevista técnica

- **Inversão de Controle / Injeção de Dependência**: `@Autowired` + programar contra interfaces (`CategoryService`, `ModelMapper` como bean gerenciado).
- **ORM e geração de schema**: `@Entity`, `@Id`, `@GeneratedValue(strategy = GenerationType.IDENTITY)` — Hibernate cria a tabela automaticamente a partir da entidade (`ddl-auto` implícito do H2 em memória).
- **Query Methods do Spring Data**: `findByCategoryName(String categoryName)` — o Spring Data gera a implementação SQL a partir do nome do método, sem escrever `@Query`.
- **Separação Entity vs DTO**: por que a API não deveria devolver a entidade JPA diretamente (evita vazar detalhes de persistência, evita problemas de serialização com proxies do Hibernate, dá controle total sobre o que trafega na API).
- **Tratamento de exceções global com `@RestControllerAdvice`/`@ExceptionHandler`**: como o Spring MVC intercepta exceções não capturadas nos controllers.
- **Bean Validation**: anotações declarativas de validação e como o Spring dispara `MethodArgumentNotValidException`.

## 9. Pontos de atenção / melhorias conhecidas

Ótimo material para entrevista ("o que você faria diferente / o que sabe que está incompleto"):

**Ainda em aberto:**
1. **Sem testes automatizados** — não há testes unitários (`CategoryServiceImplTest`) nem de integração (`@SpringBootTest` no controller) ainda.
2. **Sem camada de segurança** — não há Spring Security configurado; os prefixos `/public/` vs `/admin/` sugerem a intenção, mas não há autenticação/autorização implementada.
3. **`sortBy` não é validado** — `Sort.by(sortBy)` aceita qualquer string vinda da query; se o cliente mandar um nome de campo inexistente em `Category`, o erro só aparece em runtime (não é validado antes de chegar no banco).
4. **`APIResponse.status` sempre `false` nos handlers atuais** — o campo existe para indicar sucesso/falha, mas só é usado no caminho de erro; se o projeto padronizar respostas de sucesso simples nesse formato também, o campo passa a ter uso simétrico.
5. **Sem handler genérico para exceções inesperadas** — `MyGlobalExceptionHandler` só trata `MethodArgumentNotValidException`, `ResourceNotFoundException` e `APIException`; qualquer outra exceção (ex.: `IllegalArgumentException` de um `pageNumber` negativo em `PageRequest.of`) cai no tratamento padrão do Spring, fora do formato `APIResponse` usado no resto da API.
6. **`Category.categoryName` sem `@Size(max=...)`** — só tem `min = 5`; nomes muito longos só falham no banco (truncamento), não com um 400 amigável.
7. **`CategoryResponse.totalpages` foge do padrão camelCase** — deveria ser `totalPages`, para consistência com `pageNumber`/`pageSize`/`totalElements`.
8. **`APIResponse.message` é campo `public`** — inconsistente com `status`, que é `private` com getter/setter gerado pelo Lombok.

**Já corrigidos (histórico):**
9. ~~`updateCategory` não retorna os dados atualizados~~ — corrigido: o Controller agora devolve `ResponseEntity<CategoryDTO>` com o DTO atualizado.
10. ~~Sem paginação real~~ — corrigido: `getAllCategories` agora usa `Pageable`/`Sort`/`Page<Category>`, com `CategoryResponse` carregando os metadados de paginação.
11. ~~`ModelMapper` sem bean explícito~~ (corrigido em 2026-09-21) — a classe estava sendo `@Autowired` em `CategoryServiceImpl` sem nenhum `@Bean` que a registrasse no contexto Spring, o que quebraria a aplicação na subida (`NoSuchBeanDefinitionException`). Corrigido criando `config/AppConfig.java` com `@Bean public ModelMapper modelMapper()`.
12. ~~Sem DTO na entrada dos endpoints POST/PUT~~ — corrigido: `createCategory`/`updateCategory` agora recebem `CategoryDTO` no `@RequestBody`; a entidade `Category` não atravessa mais o Controller.
13. ~~Validação de entrada quebrada silenciosamente~~ (corrigido em 2026-09-21) — quando o Controller passou a validar `CategoryDTO` em vez de `Category`, as anotações `@NotBlank`/`@Size` ficaram "órfãs" na entidade, que não é mais validada diretamente. Corrigido movendo as anotações para `CategoryDTO`.
14. ~~`categoryId` vazando no POST de criação~~ (corrigido em 2026-09-21) — o `ModelMapper` copiava também um `categoryId` enviado no corpo, reintroduzindo o bug de `StaleObjectStateException` (item 11 da lista original de correções). Corrigido zerando `category.setCategoryId(null)` antes de salvar.
15. ~~Race condition no nome duplicado~~ (corrigido em 2026-09-21) — a checagem (`findByCategoryName`) e a gravação (`save`) eram operações separadas, sem trava, vulneráveis a requisições concorrentes. Corrigido com `@Column(unique = true)` no banco + captura de `DataIntegrityViolationException`.
16. ~~`application.properties` com propriedade incorreta~~ (corrigido em 2026-09-21) — `spring.jpa.properties.hibernate.format_sql` estava recebendo o valor `create-drop` (que pertence a `ddl-auto`). Separado em duas propriedades corretas.

## 10. Perguntas comuns de entrevista sobre este projeto (e como responder)

**"Por que separar Service de ServiceImpl?"**
→ Ver seção 1 de `notas-de-estudo.md`. Resumo: desacoplamento do contrato, testabilidade (mock de interface), convenção Spring.

**"Por que usar DTO em vez de devolver a entidade direto?"**
→ A entidade `Category` é um detalhe de persistência (tem anotações JPA, é gerenciada pelo Hibernate). O DTO (`CategoryDTO`) é o contrato público da API — podem evoluir de forma independente. Também evita expor campos internos por engano e problemas de serialização de proxies do Hibernate (lazy loading).

**"Como funciona o tratamento de erros?"**
→ Ver seção 4 de `notas-de-estudo.md`. Resumo: exceções de domínio (`APIException`, `ResourceNotFoundException`) lançadas nos services, capturadas globalmente por `MyGlobalExceptionHandler` (`@RestControllerAdvice`), que converte cada tipo em um status HTTP apropriado.

**"Como você geraria o schema do banco?"**
→ Hibernate gera automaticamente a partir das entidades (`@Entity`, `@Id`, `@GeneratedValue`), configurado via `spring.jpa.hibernate.ddl-auto` (implícito/padrão do Spring Boot com H2 em memória: `create-drop`).

**"Como você implementou paginação?"**
→ Ver seção 7 de `notas-de-estudo.md`. Resumo: `@RequestParam` no Controller lê `pageNumber`/`pageSize`/`sortBy`/`sortOrder` da query string (com defaults centralizados em `AppConstants`); o service monta um `Pageable` (`PageRequest`) com `Sort` embutido e chama `JpaRepository.findAll(Pageable)`, que devolve um `Page<Category>` com os itens da página e metadados de paginação, usados para popular `CategoryResponse`.

---

*Documento vivo — atualizar a cada nova funcionalidade, camada ou decisão de design relevante adicionada ao projeto.*
