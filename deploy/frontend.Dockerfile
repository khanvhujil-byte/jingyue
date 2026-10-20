FROM node:22-alpine AS build
WORKDIR /source
COPY frontend/ ./
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /source/dist /usr/share/nginx/html
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
