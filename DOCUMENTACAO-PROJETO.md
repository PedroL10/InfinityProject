# Documentação do Projeto — sb-ecom (API de E-commerce)

Documento de arquitetura e referência técnica do projeto, mantido como material de apoio para explicar o projeto em entrevistas. Diferente do `notas-de-estudo.md` (que registra perguntas e respostas pontuais sobre conceitos), este arquivo descreve o projeto como um todo: visão geral, arquitetura, decisões de design e pontos de atenção.

> Última atualização: 2026-10-03 (módulo de Carrinho — commit `f89628d`; pendências novas na seção 9)

---

## 1. Visão geral (pitch de entrevista)

É uma **API REST de e-commerce** construída em **Spring Boot**, com CRUD de Categorias e Produtos (com upload de imagem, paginação e busca) e um módulo completo de **autenticação stateless com JWT** (cadastro, login, logout, papéis de usuário). O projeto segue arquitetura em camadas (Controller → Service → Repository), usa **JPA/Hibernate** para persistência, **Bean Validation** para validação de entrada, **DTOs** para não expor a entidade de banco diretamente na API, **Spring Security** para autenticação/autorização, e um **tratamento de exceções centralizado** via `@RestControllerAdvice`.

Frase curta para entrevista: *"É uma API REST em Spring Boot para um sistema de e-commerce, com CRUD de Categorias e Produtos e um módulo de autenticação stateless com JWT entregue via cookie HTTP. Apliquei arquitetura em camadas, injeção de dependência via interfaces, DTOs com ModelMapper (simétrico — entrada e saída) para desacoplar a API do modelo de persistência, validação de entrada com Bean Validation, paginação e ordenação via Spring Data (`Pageable`/`Sort`), tratamento de exceções centralizado com `@RestControllerAdvice` com um envelope de resposta padronizado (`APIResponse`), e Spring Security com um filtro JWT customizado pra autenticação sem estado no servidor."*

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
| **Spring Security** | Autenticação/autorização, filtro de requisições, `PasswordEncoder` (BCrypt) |
| **JJWT** (`io.jsonwebtoken`) | Geração/validação de tokens JWT |
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
│   ├── CategoryController.java  # endpoints de Categoria
│   ├── ProductController.java    # endpoints de Produto
│   ├── CartController.java       # endpoints de Carrinho (/api/carts/**, /api/cart/**)
│   └── AuthController.java       # endpoints de autenticação (/api/auth/**)
├── service/
│   ├── CategoryService.java / CategoryServiceImpl.java
│   ├── ProductService.java / ProductServiceImpl.java
│   ├── CartService.java / CartServiceImpl.java   # carrinho: adicionar/atualizar/remover itens
│   └── FileService.java / FileServiceImpl.java   # upload de imagem de produto
├── repositories/
│   ├── CategoryRepository.java
│   ├── ProductRepository.java
│   ├── CartRepository.java       # inclui busca de carrinhos por produto e por e-mail do usuário
│   ├── CartItemRepository.java
│   ├── UserRepository.java
│   └── RoleRepository.java
├── model/
│   ├── Category.java   # @OneToMany Product
│   ├── Product.java    # @ManyToOne Category, @ManyToOne User (vendedor), @OneToMany CartItem
│   ├── User.java        # @ManyToMany Role/Address, @OneToMany Product, @OneToOne Cart
│   ├── Cart.java        # @OneToOne User, @OneToMany CartItem, totalPrice
│   ├── CartItem.java    # @ManyToOne Cart, @ManyToOne Product, quantity, preço e desconto no momento da inclusão
│   ├── Role.java / AppRole.java (enum)
│   └── Address.java
├── payload/
│   ├── CategoryDTO.java / CategoryResponse.java
│   ├── ProductDTO.java / ProductResponse.java
│   ├── CartDTO.java / CartItemDTO.java
│   └── APIResponse.java           # DTO envelope {message, status} para respostas de erro
├── util/
│   └── AuthUtil.java              # obtém o usuário logado a partir do SecurityContext
├── exceptions/
│   ├── ResourceNotFoundException.java
│   ├── APIException.java
│   └── MyGlobalExceptionHandler.java
└── security/
    ├── WebSecurityConfig.java          # beans de segurança, filterChain, dados de teste (CommandLineRunner)
    ├── jwt/
    │   ├── JwtUtils.java                # gera/valida token, empacota em cookie
    │   ├── AuthTokenFilter.java         # roda em toda requisição, popula o SecurityContext
    │   └── AuthEntryPointJwt.java       # devolve 401 JSON quando falta autenticação
    ├── request/
    │   ├── LoginRequest.java
    │   └── SignupRequest.java
    ├── response/
    │   ├── MessageResponse.java
    │   └── UserInfoResponse.java
    └── services/
        ├── UserDetailsImpl.java         # adapta User -> UserDetails do Spring Security
        └── UserDetailsServiceImpl.java  # busca o usuário pro Spring Security
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

### Categoria
| Método | Endpoint | Query params | Request body | Resposta de sucesso | Camada que valida/lança erro |
|---|---|---|---|---|---|
| GET | `/api/public/categories` | `pageNumber`, `pageSize`, `sortBy`, `sortOrder` | — | 200 + `CategoryResponse` | `APIException` (400) se a página não tiver categorias |
| POST | `/api/public/categories` | — | `CategoryDTO` | 201 + `CategoryDTO` criado | `@Valid` (400); `APIException` (400) se nome duplicado |
| PUT | `/api/public/categories/{categoryId}` | — | `CategoryDTO` | 200 + `CategoryDTO` atualizado | `ResourceNotFoundException` (404) |
| DELETE | `/api/admin/categories/{categoryId}` | — | — | 200 + `CategoryDTO` removido | `ResourceNotFoundException` (404) |

### Produto
| Método | Endpoint | Query params | Request body | Resposta de sucesso | Camada que valida/lança erro |
|---|---|---|---|---|---|
| POST | `/api/admin/categories/{categoryId}/product` | — | `ProductDTO` | 201 + `ProductDTO` criado | `@Valid` (400); `ResourceNotFoundException` (404) se categoria não existir; `APIException` (400) se produto já existe |
| GET | `/api/public/products` | `pageNumber`, `pageSize`, `sortBy`, `sortOrder` | — | 200 + `ProductResponse` | — |
| GET | `/api/public/categories/{categoryId}/products` | idem | — | 200 + `ProductResponse` | `ResourceNotFoundException` (404); `APIException` (400) se categoria sem produtos |
| GET | `/api/public/products/keyword/{keyword}` | idem | — | 200 + `ProductResponse` | `APIException` (400) se nada encontrado |
| PUT | `/api/admin/products/{productId}` | — | `ProductDTO` | 200 + `ProductDTO` atualizado | `ResourceNotFoundException` (404) |
| DELETE | `/api/admin/products/{productId}` | — | — | 200 + `ProductDTO` removido | `ResourceNotFoundException` (404) |
| PUT | `/api/products/{productId}/image` | — | `multipart/form-data` (`image`) | 200 + `ProductDTO` com imagem atualizada | `ResourceNotFoundException` (404) |

### Autenticação
| Método | Endpoint | Request body | Resposta de sucesso | Observação |
|---|---|---|---|---|
| POST | `/api/auth/signup` | `SignupRequest` | 200 + `MessageResponse` | 400 se username/email já existem, ou se algum `role` enviado não for `admin`/`seller`/`user` |
| POST | `/api/auth/signin` | `LoginRequest` | 200 + `UserInfoResponse` + cookie `Set-Cookie` (`httpOnly`) com o JWT | credenciais inválidas → **401** |
| GET | `/api/auth/username` | — | 200 + username (texto puro) | requer cookie JWT válido |
| GET | `/api/auth/user` | — | 200 + `UserInfoResponse` | requer cookie JWT válido |
| POST | `/api/auth/signout` | — | 200 + `MessageResponse`, cookie JWT limpo | — |

### Carrinho (todos exigem login)
| Método | Endpoint | Request body | Resposta de sucesso | Camada que valida/lança erro |
|---|---|---|---|---|
| POST | `/api/carts/products/{productId}/quantity/{quantity}` | — | 201 + `CartDTO` | `ResourceNotFoundException` (404) se produto não existe; `APIException` (400) se produto já está no carrinho, sem estoque, ou quantidade maior que o estoque |
| GET | `/api/carts` | — | 302 (`FOUND`) + lista de `CartDTO` | `APIException` (400) se não existir nenhum carrinho — ⚠️ status deveria ser 200 (ver seção 9) |
| GET | `/api/carts/users/cart` | — | 200 + `CartDTO` do usuário logado | — (NPE se o usuário ainda não tiver carrinho — ver seção 9) |
| PUT | `/api/cart/products/{productId}/quantity/{operation}` | — | 200 + `CartDTO` | `operation` = `delete` diminui 1, qualquer outro valor aumenta 1; `APIException` (400) se a quantidade resultante for negativa ou se o produto não estiver no carrinho |
| DELETE | `/api/carts/{cartId}/product/{productId}` | — | 200 + mensagem de texto | `ResourceNotFoundException` (404) se carrinho ou item não existir |

Erros (`APIException`/`ResourceNotFoundException`) sempre voltam como `APIResponse` (`{message, status: false}`); falhas de `@Valid` voltam como `Map<String,String>` (`{campo: mensagem}`); qualquer outra exceção inesperada agora também volta como `APIResponse` (500), via o handler genérico do `MyGlobalExceptionHandler`.

> **Importante**: no `WebSecurityConfig`, `/api/public/**` é `permitAll()` (leitura de categorias/produtos sem login); `/api/admin/**` fica **de propósito** fora do `permitAll()`, exigindo um cookie JWT válido (qualquer usuário autenticado — autorização por papel específico, tipo `hasRole("ADMIN")`, ainda não implementada). Veja o guia de testes (seção 11) para o passo a passo de login antes de testar os endpoints `/api/admin/**`.

## 8. Conceitos para citar numa entrevista técnica

- **Inversão de Controle / Injeção de Dependência**: `@Autowired` + programar contra interfaces (`CategoryService`, `ModelMapper` como bean gerenciado).
- **ORM e geração de schema**: `@Entity`, `@Id`, `@GeneratedValue(strategy = GenerationType.IDENTITY)` — Hibernate cria a tabela automaticamente a partir da entidade (`ddl-auto` implícito do H2 em memória).
- **Query Methods do Spring Data**: `findByCategoryName(String categoryName)` — o Spring Data gera a implementação SQL a partir do nome do método, sem escrever `@Query`.
- **Separação Entity vs DTO**: por que a API não deveria devolver a entidade JPA diretamente (evita vazar detalhes de persistência, evita problemas de serialização com proxies do Hibernate, dá controle total sobre o que trafega na API).
- **Tratamento de exceções global com `@RestControllerAdvice`/`@ExceptionHandler`**: como o Spring MVC intercepta exceções não capturadas nos controllers.
- **Bean Validation**: anotações declarativas de validação e como o Spring dispara `MethodArgumentNotValidException`.

## 9. Pontos de atenção / melhorias conhecidas

Ótimo material para entrevista ("o que você faria diferente / o que sabe que está incompleto"). Organizado por módulo e por severidade (🔴 alta, 🟡 média, 🟢 baixa/estilo).

### Ainda em aberto
1. 🟡 **Sem testes automatizados** — nem unitário nem de integração, em Categoria, Produto ou Autenticação.
2. 🟢 **`sortBy` não é validado explicitamente** contra os campos reais da entidade — um nome de campo inexistente agora é capturado pelo handler genérico (item 40) e volta como `APIResponse` 500, em vez de estourar a página de erro padrão do Spring; mas o ideal seria validar antes, devolvendo 400.
3. 🟢 **`spring.app.jwtSecret` com valor placeholder em `application.properties` versionado** — já externalizado via `${JWT_SECRET:...}`; em produção, a variável de ambiente precisa ser definida com um valor real.
4. 🟢 **Autorização por papel ainda não implementada** — `/api/admin/**` exige login (qualquer usuário autenticado), mas não restringe por papel (`hasRole("ADMIN")`/`hasRole("SELLER")`); qualquer usuário logado, independente do papel, consegue criar/editar/deletar categorias e produtos hoje.
5. 🟢 **`APIResponse.status` sempre `false` nos handlers atuais** — o campo existe para indicar sucesso/falha, mas só é usado no caminho de erro.

### Carrinho — pendências
Os itens 6 a 16 do commit `f89628d` foram corrigidos em 2026-10-05 (ver `notas-de-estudo.md`, seção 15). Permanece aberto:
- 🟡 **Reserva de estoque no checkout ainda não existe** — o carrinho só valida disponibilidade. A decisão atômica (`UPDATE ... WHERE quantity >= :qty`) deve ser feita quando o pedido for criado (ver memória `project_stock_race_condition`).
- 🔴 **Excluir produto que está em carrinho retorna 500** — `ProductServiceImpl.deleteProduct` remove os itens com `DELETE` em massa (JPQL), que deixa o `CartItem` gerenciado apontando para o produto removido (`TransientPropertyValueException`). Correção sugerida: remover os itens pela entidade, em vez de `DELETE` em massa (ver `notas-de-estudo.md`, seção 16.4).
- 🟡 **Decisão de negócio pendente** — o carrinho deve ser apagado junto com o usuário? A configuração atual faz isso (`orphanRemoval` já propagava `REMOVE`). Confirmar com o dono do projeto (ver `notas-de-estudo.md`, seção 16.3).

Corrigidos nesta rodada (seção 16 de `notas-de-estudo.md`): consulta `findCartsByProductId` que limitava os itens carregados (verificada com a sincronização de preço).


### Já corrigidos (histórico)
19. ~~`updateCategory` não retorna os dados atualizados~~ — corrigido: devolve `CategoryDTO` atualizado.
20. ~~Sem paginação real~~ — corrigido com `Pageable`/`Sort`/`Page<Category>`.
21. ~~`ModelMapper` sem bean explícito~~ (2026-09-21) — corrigido com `config/AppConfig.java`.
22. ~~Sem DTO na entrada dos endpoints POST/PUT de Categoria~~ — corrigido.
23. ~~Validação de entrada quebrada silenciosamente em `CategoryDTO`~~ (2026-09-21) — corrigido movendo `@NotBlank`/`@Size` da entidade pro DTO.
24. ~~`categoryId` vazando no POST de criação~~ (2026-09-21) — corrigido com `category.setCategoryId(null)`.
25. ~~Race condition no nome duplicado de Categoria~~ (2026-09-21) — corrigido com `@Column(unique = true)` + captura de `DataIntegrityViolationException`.
26. ~~`application.properties` com `format_sql` recebendo valor de `ddl-auto`~~ (2026-09-21) — corrigido.
27. ~~`ProductDTO` sem validação~~ (2026-09-22) — mesma falha do item 23, agora em Produto; corrigido espelhando `@NotBlank`/`@Size` de `Product`.
28. ~~`productId` vazando no POST de criação de Produto~~ (2026-09-22) — mesma falha do item 24; corrigido com `product.setProductId(null)`.
29. ~~`getProductsByKeyword` devolvia `HttpStatus.FOUND` (302)~~ (2026-09-22) — corrigido para `HttpStatus.OK`.
30. ~~`ProductDTO.specialPrice` primitivo causava crash de desserialização~~ (2026-09-22) — Jackson não conseguia mapear `null` num `double`; corrigido trocando para `Double` (wrapper).
31. ~~`updateProduct` não recalculava `specialPrice`~~ (2026-09-22) — copiava o valor do DTO em vez de recalcular a partir de `price`/`discount`; corrigido para recalcular, igual `addProduct`.
32. ~~Pacotes `security.jwt` declarados errados~~ (2026-10-02) — arquivos moveram fisicamente para `security/jwt/`, pacote corrigido.
33. ~~Import `com.fasterxml.jackson.databind.ObjectMapper` incompatível~~ (2026-10-02) — projeto usa Jackson 3 (Spring Boot 4.1); corrigido para `tools.jackson.databind.ObjectMapper`.
34. ~~`DaoAuthenticationProvider()` sem argumentos e `setUserDetailsService(...)` removidos no Spring Security 7~~ (2026-10-02) — corrigido usando `new DaoAuthenticationProvider(userDetailsService)`.
35. ~~`/api/admin/**`/`/api/public/**` comentados~~ (2026-10-02) — `/api/public/**` voltou a ser `permitAll()`; `/api/admin/**` fica protegido de propósito (exige login).
36. ~~Credenciais inválidas no login devolviam 404~~ (2026-10-02) — corrigido para 401 Unauthorized.
37. ~~Cookie JWT com `httpOnly(false)`~~ (2026-10-02) — corrigido para `httpOnly(true)`.
38. ~~Race condition no cadastro~~ (2026-10-02) — corrigido com captura de `DataIntegrityViolationException` ao redor do `save()`.
39. ~~`UserDetailsImpl` sem `hashCode()`~~ (2026-10-02) — adicionado, consistente com `equals()` (baseado em `id`).
40. ~~Papéis de cadastro inválidos caíam silenciosamente em `ROLE_USER`~~ (2026-10-02) — agora retornam 400 explicitamente.
41. ~~`Product.productName` sem constraint única~~ (2026-10-02) — adicionada constraint composta `(category_id, product_name)`, refletindo a regra real (nome único por categoria, não globalmente).
42. ~~`updateProduct` não validava nome duplicado~~ (2026-10-02) — corrigido com a constraint acima + captura de `DataIntegrityViolationException`.
43. ~~`Product` usava `GenerationType.AUTO`~~ (2026-10-02) — alinhado para `IDENTITY`, como `Category`/`User`.
44. ~~`Category.products` sem inicializador~~ (2026-10-02) — corrigido com `= new ArrayList<>()`.
45. ~~Sem handler genérico de exceções~~ (2026-10-02) — adicionado `@ExceptionHandler(Exception.class)` em `MyGlobalExceptionHandler`.
46. ~~`Category.categoryName` sem `@Size(max=...)`~~ (2026-10-02) — adicionado `max = 100`.
47. ~~`APIResponse.message` era campo `public`~~ (2026-10-02) — corrigido para `private`.
48. ~~Injeção por campo em vez de construtor~~ (2026-10-02) — convertido para `@RequiredArgsConstructor`/`final` em `CategoryController`, `CategoryServiceImpl`, `ProductController`, `ProductServiceImpl`, `AuthController`, `WebSecurityConfig`, `UserDetailsServiceImpl` e `AuthTokenFilter`.
49. ~~Arquivos órfãos `security/jwt/LoginRequest.java`/`LoginResponse.java`~~ (2026-10-02) — duplicados sem nenhuma referência no projeto, removidos.
50. ~~`updateProduct` sem tratamento de duplicidade (regressão do commit `f89628d`)~~ (2026-10-05) — `saveAndFlush` dentro de `try/catch` restaurado.
51. ~~Estoque sobrescrito por leitura do carrinho (`getCart`)~~ (2026-10-05) — quantidade definida só no `ProductDTO`.
52. ~~Ciclos de `equals`/`hashCode`/`toString` entre entidades~~ (2026-10-05) — `@EqualsAndHashCode.Exclude` e `@ToString.Exclude` no lado de volta.
53. ~~`Product.products` mal nomeado e `EAGER`~~ (2026-10-05) — campo removido (não era usado).
54. ~~`updateProductQuantityInCart` salvava item já removido~~ (2026-10-05) — fluxo reescrito com retorno antecipado e total recalculado.
55. ~~NPE sem carrinho~~ (2026-10-05) — 404 em `GET /api/carts/users/cart` e 400 em `PUT`, com mensagem clara.
56. ~~Lógica do carrinho do usuário no controller~~ (2026-10-05) — movida para `CartService.getLoggedUserCart()`.
57. ~~`@Autowired` em campo em `ProductServiceImpl`, `CartServiceImpl` e `CartController`~~ (2026-10-05) — injeção por construtor.
58. ~~`GET /api/carts` retornava 302~~ (2026-10-05) — agora 200.
59. ~~Comentário `// DELETE` solto~~ (2026-10-05) — removido.
60. ~~Item recém-adicionado omitido na resposta de `addProductToCart`~~ (2026-10-05) — achado na revisão; corrigido.

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

**"Como funciona a autenticação?"**
→ Ver seção 10 de `notas-de-estudo.md`. Resumo: JWT stateless entregue via cookie HTTP; `AuthTokenFilter` roda em toda requisição, extrai e valida o token, e repopula o `SecurityContext` sem depender de sessão no servidor.

## 11. Guia de testes no Postman

Reinicie a aplicação antes de começar (H2 em memória — dados são recriados do zero, inclusive os usuários de teste do `CommandLineRunner`). Habilite "Automatically follow redirects" e deixe o gerenciador de cookies do Postman ativo (padrão) — ele guarda o cookie JWT automaticamente entre requisições, como um navegador faria.

> `/api/public/**` (leitura de categorias/produtos) não exige login. **`/api/admin/**` exige** — é preciso logar antes de criar/editar/deletar categorias ou produtos, senão a chamada devolve 401 do `AuthEntryPointJwt`.

### 11.1 Autenticação

**Usuários já existentes** (criados pelo `CommandLineRunner` na subida — não precisa cadastrar pra testar):
| username | password | papéis |
|---|---|---|
| `user1` | `password1` | ROLE_USER |
| `seller1` | `password2` | ROLE_SELLER |
| `admin` | `adminPass` | ROLE_USER, ROLE_SELLER, ROLE_ADMIN |

**1. Cadastro de um novo usuário**
```
POST /api/auth/signup
```
```json
{
    "username": "pedro",
    "email": "pedro@example.com",
    "password": "minhaSenha123",
    "role": ["seller"]
}
```
✅ Esperado: 200, `{"message": "User registered successfully!"}`. `role` é opcional — omitindo, o usuário vira `ROLE_USER`. Valores aceitos: `"admin"`, `"seller"`, `"user"`; qualquer outro valor devolve 400 explicando que o papel é inválido.

Repetir o mesmo body → 400, `{"message": "Error: Username is already taken!"}`.

**2. Login**
```
POST /api/auth/signin
```
```json
{
    "username": "admin",
    "password": "adminPass"
}
```
✅ Esperado: 200, corpo `UserInfoResponse` (`id`, `username`, `roles`, `jwtToken`) **e** um header `Set-Cookie` com o token — o Postman guarda esse cookie automaticamente para as próximas requisições na mesma aba/environment.

Senha errada:
```json
{ "username": "admin", "password": "errada" }
```
✅ Esperado: **401 Unauthorized**, `{"message": "Bad credentials", "status": false}`.

**3. Dados do usuário logado**
```
GET /api/auth/user
```
(sem body — usa o cookie salvo automaticamente)
✅ Esperado: 200, `UserInfoResponse` sem `jwtToken`.

**4. Username do usuário logado**
```
GET /api/auth/username
```
✅ Esperado: 200, texto puro com o username.

**5. Logout**
```
POST /api/auth/signout
```
✅ Esperado: 200, `{"message": "You've been signed out!"}`, e o cookie JWT é limpo — chamadas seguintes a endpoints protegidos voltam a dar 401.

### 11.2 Categoria (logado como `admin`, por exemplo)

**6. Criar categoria**
```
POST /api/public/categories
```
```json
{ "categoryName": "Electronics" }
```
✅ 201 + `CategoryDTO` criado (anote o `categoryId`).

**7. Listar categorias**
```
GET /api/public/categories?pageNumber=0&pageSize=10&sortBy=categoryName&sortOrder=asc
```
✅ 200 + `CategoryResponse`.

**8. Atualizar categoria**
```
PUT /api/public/categories/{categoryId}
```
```json
{ "categoryName": "Electronics & Gadgets" }
```
✅ 200 + `CategoryDTO` atualizado.

**9. Deletar categoria**
```
DELETE /api/admin/categories/{categoryId}
```
✅ 200 + `CategoryDTO` removido.

### 11.3 Produto (precisa de uma categoria válida — repita o passo 6 antes)

**10. Criar produto**
```
POST /api/admin/categories/{categoryId}/product
```
```json
{
    "productName": "Wireless Mouse",
    "description": "Ergonomic wireless mouse",
    "quantity": 50,
    "price": 120.00,
    "discount": 10
}
```
✅ 201 + `ProductDTO` criado (`specialPrice` calculado automaticamente: `120 - 12 = 108`; `image: "default.png"`).

**11. Listar todos os produtos**
```
GET /api/public/products?pageNumber=0&pageSize=10&sortBy=productId&sortOrder=asc
```
✅ 200 + `ProductResponse`.

**12. Listar produtos de uma categoria**
```
GET /api/public/categories/{categoryId}/products
```
✅ 200 + `ProductResponse`.

**13. Buscar por palavra-chave**
```
GET /api/public/products/keyword/mouse
```
✅ 200 + `ProductResponse` (produtos cujo nome contém "mouse", case-insensitive).

**14. Atualizar produto**
```
PUT /api/admin/products/{productId}
```
```json
{
    "productName": "Wireless Mouse Pro",
    "description": "Ergonomic wireless mouse, upgraded",
    "quantity": 40,
    "price": 150.00,
    "discount": 15
}
```
✅ 200 + `ProductDTO` atualizado (`specialPrice` recalculado: `150 - 22.5 = 127.5`).

**15. Atualizar imagem do produto**
```
PUT /api/products/{productId}/image
```
Body → `form-data`, chave `image` do tipo **File**, selecione uma imagem local.
✅ 200 + `ProductDTO` com `image` trocado pro nome gerado (`uuid.extensão`), salvo em `images/`.

**16. Deletar produto**
```
DELETE /api/admin/products/{productId}
```
✅ 200 + `ProductDTO` removido.

### 11.4 Carrinho (logado como `user1`, por exemplo — o carrinho é criado sob demanda na primeira inclusão)

Antes de testar: o produto precisa existir, com estoque (`quantity`) maior que zero — use o produto criado no passo 10.

**17. Adicionar produto ao carrinho**
```
POST /api/carts/products/{productId}/quantity/2
```
(sem body — quantidade vai no path)
✅ 201 + `CartDTO` com `totalPrice` = `specialPrice × 2` e a lista de produtos.

Repetir o mesmo produto → 400, `"Product Wireless Mouse already exists in the cart"`.

**18. Ver o carrinho do usuário logado**
```
GET /api/carts/users/cart
```
✅ 200 + `CartDTO`.

**19. Aumentar 1 unidade**
```
PUT /api/cart/products/{productId}/quantity/increase
```
✅ 200 + `CartDTO` com a quantidade atualizada e `totalPrice` recalculado.

**20. Diminuir 1 unidade**
```
PUT /api/cart/products/{productId}/quantity/delete
```
✅ 200. Se a quantidade chegar a zero, o item é removido do carrinho.

**21. Remover o produto do carrinho**
```
DELETE /api/carts/{cartId}/product/{productId}
```
✅ 200, `"Product Wireless Mouse removed from the cart !!!"`.

**22. Listar todos os carrinhos (admin/teste)**
```
GET /api/carts
```
✅ Esperado: lista de carrinhos. ⚠️ O status atual é **302** (ver seção 9, item 13).

### 11.5 Checklist geral
- [ ] Sem logar, qualquer chamada a Categoria/Produto → 401 (`AuthEntryPointJwt`).
- [ ] Login com usuário/senha certos → 200 + cookie; senha errada → 404 (comportamento atual, ver seção 9).
- [ ] Cadastro com username/email repetido → 400 com mensagem clara.
- [ ] Criar categoria/produto com nome curto/vazio → 400 com erros por campo.
- [ ] Criar produto em categoria inexistente → 404.
- [ ] Atualizar/deletar categoria ou produto com ID inexistente → sempre 404, nunca 500.
- [ ] Logout seguido de uma chamada protegida → 401 de novo.

---

*Documento vivo — atualizar a cada nova funcionalidade, camada ou decisão de design relevante adicionada ao projeto.*
